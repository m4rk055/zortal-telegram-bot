package net.zortal.telegram.bot

import zio._
import net.zortal.telegram.bot.Article

import org.http4s.client.Client
import org.http4s._
import java.time.ZonedDateTime
import org.jsoup.Jsoup
import scala.xml._
import zio.interop.catz._

import java.time.ZonedDateTime
import zio.clock.Clock
import zio.random.Random

trait ZortalFeedApi {
  val zortalFeedApi: ZortalFeedApi.Service[Any]
}

object ZortalFeedApi {

  trait Service[R] {
    def getFeed(lastPublished: ZonedDateTime): ZIO[R, Throwable, List[Article]]
  }

  object > {
    def getFeed(lastPublished: ZonedDateTime) =
      ZIO.accessM[ZortalFeedApi](_.zortalFeedApi.getFeed(lastPublished))
  }

  val exponentialBackoffStrategy =
    (Schedule.spaced(30 seconds) >>>
      Schedule.exponential(1 second, 2) >>>
      Schedule.randomDelay(0 second, 1 second) >>>
      Schedule.elapsed)
      .whileOutput(_ < 30.minutes) andThen
      Schedule.spaced(30 minutes)

  def apply(
    httpClient: Client[Task],
    endPoint: Uri,
  ) = new ZortalFeedApi {

    val zortalFeedApi = new ZortalFeedApi.Service[Any] {

      def getFeed(lastPublished: ZonedDateTime) =
        for {
          resp <- httpClient
                   .expect[String](endPoint)
                   .retry(exponentialBackoffStrategy)
                   // TODO: is there a better way?
                   .provide(new Clock.Live with Random.Live)

          xml <- ZIO.effect(XML.loadString(resp))
          newArticles <- ZIO.effect(
                          (xml \ "entry").map { node =>
                            val title     = Jsoup.parse((node \ "title").text).text()
                            val published = ZonedDateTime.parse((node \ "published").text)
                            val link      = ((node \ "link").head \@ "href")

                            Article(published, title, link)
                          }.filter(_.published.isAfter(lastPublished)).toList,
                        )

        } yield newArticles
    }
  }
}
