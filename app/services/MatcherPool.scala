package services

import java.util.concurrent.ConcurrentHashMap

import net.logstash.logback.marker.Markers

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}
import play.api.Logging
import model.{BaseRule, Category, Check, MatcherResponse, RuleMatch, TextBlock}
import utils.Matcher
import akka.NotUsed
import akka.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed, Failure => QueueFailure}
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import model._
import play.api.Logging
import services.MatcherPool.CheckStrategy
import utils.Matcher

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}

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
case class MatcherJob(requestId: String, documentId: String, blocks: List[TextBlock], categoryIds: List[String], promise: Promise[List[RuleMatch]], jobsInValidationSet: Integer) {
  def toMarker = Markers.appendEntries(Map(
    "requestId" -> this.requestId,
    "documentId" -> this.documentId,
    "blocks" -> this.blocks.map(_.id).mkString(", "),
    "categoryIds" -> this.categoryIds.mkString(", ")
  ).asJava)
}

case class MatcherJobResult(job: MatcherJob, matches: List[RuleMatch])

object MatcherPool extends Logging {
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

class MatcherPool(val maxCurrentJobs: Int = 8, val maxQueuedJobs: Int = 1000, val checkStrategy: MatcherPool.CheckStrategy = MatcherPool.documentPerCategoryCheckStrategy)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends Logging {

  private val matchers = new ConcurrentHashMap[String, (Category, Matcher)]().asScala
  private val queue = Source.queue[MatcherJob](maxQueuedJobs, OverflowStrategy.dropNew)
    .mapAsyncUnordered(maxCurrentJobs)(runValidationJob)
    .to(Sink.ignore)
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

    logger.info(s"Matcher pool query received")(query.toMarker)

    val eventuallyResponses =
      createJobsFromPartialJobs(query.requestId, query.documentId.getOrElse("no-document-id"), checkStrategy(query.blocks, categoryIds))
      .map(offerJobToQueue)

    Future.sequence(eventuallyResponses).map { results =>
      logger.info(s"Validation job with id: ${query.requestId} complete")
      MatcherResponse(
        results.flatMap(_.job.blocks).distinct,
        results.flatMap(_.job.categoryIds).distinct,
        results.flatMap(_.matches)
      )
    }
  }

  def check(query: Check): Future[MatcherResponse] = check(query, checkStrategy)

  def checkStream(query: Check, checkStrategy: CheckStrategy): Source[MatcherResponse, NotUsed] = {
    val categoryIds = query.categoryIds match {
      case None => getCurrentCategories.map { case (_, category) => category.id }
      case Some(ids) => ids
    }

    logger.info(s"Validation job with id: ${query.requestId} received. Checking categories: ${categoryIds.mkString(", ")}")

    val jobs = createJobsFromPartialJobs(query.requestId, query.documentId.getOrElse("no-document-id"), checkStrategy(query.blocks, categoryIds))
    logger.info(s"Created ${jobs.size} jobs")

    val eventualResponses = jobs map offerJobToQueue
    val responseStream = Source(eventualResponses).mapAsyncUnordered(1)(identity)

    responseStream.map { result =>
      MatcherResponse(
        result.job.blocks,
        result.job.categoryIds,
        result.matches
      )
    }
  }

  /**
    * Add a matcher to the pool of matchers for the given category.
    * Replaces a matcher that's already present for that category, returning
    * the replaced matcher.
    */
  def addMatcher(category: Category, matcher: Matcher): Option[(Category, Matcher)] = {
    logger.info(s"New instance of matcher available of id: ${matcher.getId} for category: ${category.id}")
    matchers.put(category.id, (category, matcher))
  }

  /**
    * Remove a matcher from the pool by its category id.
    * Returns the removed category and matcher.
    */
  def removeMatcherByCategory(categoryId: String): Option[(Category, Matcher)] = {
    matchers.remove(categoryId)
  }

  def removeAllMatchers(): Unit = {
    matchers.map(_._1).foreach(removeMatcherByCategory)
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

  private def createJobsFromPartialJobs(requestId: String, documentId: String, partialJobs: List[PartialMatcherJob]) = partialJobs.map { partialJob =>
    val promise = Promise[List[RuleMatch]]
    MatcherJob(requestId, documentId, partialJob.blocks, partialJob.categoryIds, promise, partialJobs.length)
  }

  private def offerJobToQueue(job: MatcherJob): Future[MatcherJobResult] = {
    logger.info(s"Job has been offered to the queue")(job.toMarker)

    queue.offer(job).collect {
      case Enqueued =>
        logger.info(s"Job ${getJobMessage(job)} enqueued")
      case Dropped =>
        failJobWith(job, "Job was dropped from the queue, as the queue is full")
      case QueueClosed =>
        failJobWith(job, s"Job failed because the queue is closed")
      case QueueFailure(err) =>
        failJobWith(job, s"Job failed, reason: ${err.getMessage}")
    }

    job.promise.future.andThen {
      case result => {
        logger.info(s"Job is complete")(job.toMarker)
        result
      }
    }.map {
      MatcherJobResult(job, _)
    }
  }

  private def failJobWith(job: MatcherJob, message: String) = {
    logger.error(message)(job.toMarker)
    job.promise.failure(new Throwable(message))
  }

  private def runValidationJob(job: MatcherJob): Future[MatcherJobResult] = {
    val jobResults = job.categoryIds.map { categoryId =>
      matchers.get(categoryId) match {
        case Some((_, matcher)) =>
          val eventuallyMatches = matcher.check(MatcherRequest(job.blocks, categoryId))
          job.promise.completeWith(eventuallyMatches)
          eventuallyMatches
        case None =>
          val message = s"Could not run job with -- unknown category for id: $categoryId"
          logger.error(message)(job.toMarker)
          val error = new IllegalStateException(message)
          job.promise.failure(error)
          Future.failed(error)
      }
    }
    Future.sequence(jobResults).map { results =>
      MatcherJobResult(job, results.flatten)
    }
  }

  private def getJobMessage(job: MatcherJob) = s"with blocks: ${getJobBlockIdsAsString(job)} for categories: ${job.categoryIds.mkString(", ")}"

  private def getJobBlockIdsAsString(job: MatcherJob) = job.blocks.map(_.id).mkString(", ")
}

