package services

import java.util.concurrent.ConcurrentHashMap

import akka.NotUsed

import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import play.api.Logger
import model.{BaseRule, Category, Check, MatcherResponse, RuleMatch, TextBlock}
import utils.Matcher
import akka.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed, Failure => QueueFailure}
import akka.stream._
import akka.stream.scaladsl.{BroadcastHub, Sink, Source}
import services.MatcherPool.CheckStrategy

import scala.concurrent.duration.Duration

case class MatcherRequest(blocks: List[TextBlock], categoryId: String)

/**
  * A PartialMatcherJob represents the information our CheckStrategy needs to divide validation work into jobs.
  */
case class PartialMatcherJob(blocks: List[TextBlock], categoryIds: List[String])

/**
  * A MatcherJob represents everything we need to for a matcher to
  *  - perform work
  *  - notify its caller once work is complete
  *
  */
case class MatcherJob(blocks: List[TextBlock], categoryIds: List[String], requestId: String, promise: Promise[List[RuleMatch]], jobsInValidationSet: Integer)

case class MatcherJobResult(job: MatcherJob, matches: List[RuleMatch])

object MatcherPool {
  type CategoryIds = List[String]
  type CheckStrategy = (List[TextBlock], CategoryIds) => List[PartialMatcherJob]


  /**
    * This strategy divides the document into single blocks, and evaluates each block
    * against all of the passed categories.
    */
  def blockLevelCheckStrategy: CheckStrategy = (blocks, categoryIds) => {
    blocks.map(block => PartialMatcherJob(List(block), categoryIds))
  }

  /**
    * This strategy evaluates whole documents at once, and evaluates the document
    * separately against each of the passed categories.
    */
  def documentPerCategoryCheckStrategy: CheckStrategy = (blocks, categoryIds) => {
    categoryIds.map(categoryId => PartialMatcherJob(blocks, List(categoryId)))
  }
}

class MatcherPool(val maxCurrentJobs: Int = 8, val maxQueuedJobs: Int = 1000, val defaultCheckStrategy: MatcherPool.CheckStrategy = MatcherPool.documentPerCategoryCheckStrategy)(implicit ec: ExecutionContext, implicit val mat: Materializer) {
  type JobProgressMap = Map[String, Int]

  private val matchers = new ConcurrentHashMap[String, (Category, Matcher)]().asScala
  private val eventBus = new MatcherPoolEventBus()
  private val queue = Source.queue[MatcherJob](maxQueuedJobs, OverflowStrategy.dropNew)
    .async
    .mapAsyncUnordered(maxCurrentJobs)(runValidationJob)
    .async
    .to(Sink.fold(Map[String, Int]())(markJobAsComplete))
    .run()

  def getMaxCurrentValidations: Int = maxCurrentJobs
  def getMaxQueuedValidations: Int = maxQueuedJobs

  /**
    * Check the text with the matchers assigned to the given category ids.
    * If no ids are assigned, use all the currently available matchers.
    */
  def check(query: Check, checkStrategy: CheckStrategy): Future[MatcherResponse] = {
    val categoryIds = query.categoryIds match {
      case None => getCurrentCategories.map { case (_, category) => category.id }
      case Some(ids) => ids
    }

    Logger.info(s"Validation job with id: ${query.requestId} received. Checking categories: ${categoryIds.mkString(", ")}")

    val eventuallyResponses =
      createJobsFromPartialJobs(defaultCheckStrategy(query.blocks, categoryIds), query.requestId)
      .map(offerJobToQueue)

    Future.sequence(eventuallyResponses).map { results =>
      Logger.info(s"Validation job with id: ${query.requestId} complete")
      MatcherResponse(
        results.flatMap(_.job.blocks).distinct,
        results.flatMap(_.job.categoryIds).distinct,
        results.flatMap(_.matches)
      )
    }
  }

  def check(query: Check): Future[MatcherResponse] = check(query, defaultCheckStrategy)

  def checkStream(query: Check, checkStrategy: CheckStrategy): Source[MatcherResponse, NotUsed] = {
    val categoryIds = query.categoryIds match {
      case None => getCurrentCategories.map { case (_, category) => category.id }
      case Some(ids) => ids
    }

    Logger.info(s"Validation job with id: ${query.requestId} received. Checking categories: ${categoryIds.mkString(", ")}")

    val jobs = createJobsFromPartialJobs(checkStrategy(query.blocks, categoryIds), query.requestId)
    Logger.info(s"Created ${jobs.size} jobs")
    val eventualResponses = jobs map offerJobToQueue
    val responsesAsSources = eventualResponses map Source.fromFuture
    val mergedResponses = responsesAsSources.foldLeft(Source.empty[MatcherJobResult]) { case (source, acc) =>
        acc.merge(source)
    }
    mergedResponses.map { result =>
      MatcherResponse(
        result.job.blocks,
        result.job.categoryIds,
        result.matches
      )
    }
  }

  /**
    * @see MatcherPoolEventBus
    */
  def subscribe(subscriber: MatcherPoolSubscriber): Boolean = eventBus.subscribe(subscriber, subscriber.requestId)

  /**
    * @see MatcherPoolEventBus
    */
  def unsubscribe(subscriber: MatcherPoolSubscriber): Boolean = eventBus.unsubscribe(subscriber, subscriber.requestId)

  /**
    * Add a matcher to the pool of matchers for the given category.
    * Replaces a matcher that's already present for that category, returning
    * the replaced matcher.
    */
  def addMatcher(category: Category, matcher: Matcher): Option[(Category, Matcher)] = {
    Logger.info(s"New instance of matcher available of id: ${matcher.getId} for category: ${category.id}")
    matchers.put(category.id, (category, matcher))
  }

  def getCurrentCategories: List[(String, Category)] = {
    val matchersAndCategories = matchers.values.map {
      case (category, matcher) => (matcher.getId, category)
    }.toList
    matchersAndCategories
  }

  def getCurrentRules: List[BaseRule] = {
    matchers.values.flatMap {
      case (_, matcher) =>  matcher.getRules
    }.toList
  }

  private def createJobsFromPartialJobs(partialJobs: List[PartialMatcherJob], requestId: String) = partialJobs.map { partialJob =>
    val promise = Promise[List[RuleMatch]]
    MatcherJob(partialJob.blocks, partialJob.categoryIds, requestId, promise, partialJobs.length)
  }

  private def offerJobToQueue(job: MatcherJob): Future[MatcherJobResult] = {
    Logger.info(s"Job ${getJobMessage(job)} has been offered to the queue")

    queue.offer(job).collect {
      case Enqueued =>
        Logger.info(s"Job ${getJobMessage(job)} enqueued")
      case Dropped =>
        job.promise.failure(new Throwable(s"Job ${getJobMessage(job)} was dropped from the queue, as the queue is full"))
      case QueueClosed =>
        job.promise.failure(new Throwable(s"Job ${getJobMessage(job)} failed because the queue is closed"))
      case QueueFailure(err) =>
        job.promise.failure(new Throwable(s"Job ${getJobMessage(job)} failed, reason: ${err.getMessage}"))
    }

    job.promise.future.andThen {
      case result => {
        Logger.info(s"Job ${getJobMessage(job)} is complete")
        result
      }
    }.map {
      MatcherJobResult(job, _)
    }
  }

  private def runValidationJob(job: MatcherJob): Future[MatcherJobResult] = {
    val jobResults = job.categoryIds.map { categoryId =>
      matchers.get(categoryId) match {
        case Some((_, matcher)) =>
          val eventuallyMatches = matcher.check(MatcherRequest(job.blocks, categoryId))
          job.promise.completeWith(eventuallyMatches)
          eventuallyMatches
        case None =>
          val error = new IllegalStateException(s"Could not run validation job with blocks: ${getJobBlockIdsAsString(job)} -- unknown category for id: $categoryId")
          job.promise.failure(error)
          Future.failed(error)
      }
    }
    Future.sequence(jobResults).map { results =>
      MatcherJobResult(job, results.flatten)
    }
  }

  private def markJobAsComplete(progressMap: Map[String, Int], result: MatcherJobResult): JobProgressMap = {
    val newCount = progressMap.get(result.job.requestId) match {
      case Some(jobCount) => jobCount - 1
      case None => result.job.jobsInValidationSet - 1
    }

    publishResults(result)

    if (newCount == 0) {
      publishJobsComplete(result.job.requestId)
    }

    progressMap + (result.job.requestId -> newCount)
  }

  private def publishResults(result: MatcherJobResult): Unit = {
    eventBus.publish(MatcherPoolResultEvent(
      result.job.requestId,
      MatcherResponse(
        result.job.blocks,
        result.job.categoryIds,
        result.matches
      )
    ))
  }

  private def publishJobsComplete(requestId: String): Unit = {
    eventBus.publish(MatcherPoolJobsCompleteEvent(requestId))
  }

  private def getJobMessage(job: MatcherJob) = s"with blocks: ${getJobBlockIdsAsString(job)} for categories: ${job.categoryIds.mkString(", ")}"

  private def getJobBlockIdsAsString(job: MatcherJob) = job.blocks.map(_.id).mkString(", ")
}

