import java.io.File

import akka.actor.ActorSystem
import akka.dispatch.MessageDispatcher
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer}

import scala.concurrent.Future
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, InstanceProfileCredentialsProvider}
import com.gu.{AppIdentity, AwsIdentity}
import controllers.{ApiController, HomeController, RulesController}
import play.api.ApplicationLoader.Context
import play.api.{BuiltInComponentsFromContext, Configuration}
import play.api.libs.concurrent.ActorSystemProvider
import play.api.mvc.EssentialFilter
import play.filters.HttpFiltersComponents
import play.filters.cors.CORSComponents
import router.Routes
import rules.SheetsRuleResource
import services.{ElkLogging, LanguageToolFactory, MatcherPool}
import services._
import utils.Loggable

class AppComponents(context: Context, identity: AppIdentity)
  extends BuiltInComponentsFromContext(context)
  with HttpFiltersComponents
  with CORSComponents
  with Loggable
  with controllers.AssetsComponents {

  override def httpFilters: Seq[EssentialFilter] = corsFilter +: super.httpFilters.filterNot(allowedHostsFilter ==)

  private val awsCredentialsProvider = new AWSCredentialsProviderChain(
    InstanceProfileCredentialsProvider.getInstance(),
    new ProfileCredentialsProvider(configuration.get[String]("typerighter.defaultAwsProfile"))
  )

  // initialise log shipping if we are in AWS
  private val logShipping = Some(identity).collect{ case awsIdentity: AwsIdentity =>
    val loggingStreamName = configuration.getOptional[String]("typerighter.loggingStreamName")
    new ElkLogging(awsIdentity, loggingStreamName, awsCredentialsProvider, applicationLifecycle)
  }

  private val ngramPath: Option[File] = configuration.getOptional[String]("typerighter.ngramPath").map(new File(_))
  private val languageToolFactory = new LanguageToolFactory(ngramPath)
  private val matchDispatcherName = "matcher-pool-dispatcher"
  private val matcherDispatcher = actorSystem.dispatchers.lookup(matchDispatcherName)
  private val matcherMaterializer = ActorMaterializer(ActorMaterializerSettings(actorSystem).withDispatcher(matchDispatcherName))(actorSystem)
  private val matcherPool = new MatcherPool()(matcherDispatcher, matcherMaterializer)

  private val credentials = configuration.get[String]("typerighter.google.credentials")
  private val spreadsheetId = configuration.get[String]("typerighter.sheetId")
  private val range = configuration.get[String]("typerighter.sheetRange")
  private val ruleResource = new SheetsRuleResource(credentials, spreadsheetId, range)

  private val apiController = new ApiController(controllerComponents, matcherPool)
  private val rulesController = new RulesController(controllerComponents, matcherPool, languageToolFactory, ruleResource, spreadsheetId)
  private val homeController = new HomeController(controllerComponents)

  initialiseMatchers

  lazy val router = new Routes(
    httpErrorHandler,
    assets,
    homeController,
    rulesController,
    apiController
  )

  /**
    * Set up matchers and add them to the matcher pool as the app starts.
    */
  def initialiseMatchers: Future[Unit] = {
    for {
      (rulesByCategory, _) <- ruleResource.fetchRulesByCategory()
    } yield {
      rulesByCategory.foreach { case (category, rules) => {
        val matcher = new RegexMatcher(category.name, rules)
        matcherPool.addMatcher(category, matcher)
      }}
    }
  }
}
