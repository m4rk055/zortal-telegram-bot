package net.zortal.telegram.bot

import zio._
import zio.stream.Stream
import zio.duration.Duration
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
import zio.stream.ZStream

trait ZortalFeedApi {
  val zortalFeedApi: ZortalFeedApi.Service[Any]
}

object ZortalFeedApi {

  trait Service[R] {
    def getFeed(delay: Duration): Stream[Throwable, List[Article]]
  }

  object > {
    def getFeed(delay: Duration) =
      ZStream.unwrap(ZIO.access[ZortalFeedApi](_.zortalFeedApi.getFeed(delay)))
  }

  val exponentialBackoffStrategy =
    (Schedule.spaced(30 seconds) >>>
      Schedule.exponential(1 second, 2) >>>
      Schedule.randomDelay(0 second, 1 second) >>>
      Schedule.elapsed)
      .whileOutput(_ < 30.minutes) andThen
      Schedule.spaced(30 minutes)

  def apply(
    clockService: Clock.Service[Any],
    randomService: Random.Service[Any],
    httpClient: Client[Task],
    endPoint: Uri,
    startTime: ZonedDateTime,
    onGetFeedError: Throwable => Task[Unit],
  ) = new ZortalFeedApi {

    val zortalFeedApi = new ZortalFeedApi.Service[Any] {

      def getFeed(delay: Duration) =
        Stream
          .unfoldM[Throwable, List[Article], ZonedDateTime](startTime) { lastPublished =>
            val elem = for {
              resp <- httpClient
                       .expect[String](endPoint)
                       .retry(exponentialBackoffStrategy)

              _ = println(resp.take(10))

              xml <- ZIO.effect(XML.loadString(resp))
              newArticles <- ZIO.effect(
                              (xml \ "entry").map { node =>
                                val title     = Jsoup.parse((node \ "title").text).text()
                                val published = ZonedDateTime.parse((node \ "published").text)
                                val link      = ((node \ "link").head \@ "href")

                                Article(published, title, link)
                              }.filter(_.published.isAfter(lastPublished)).toList,
                            )
              newPublished <- ZIO(newArticles.map(_.published).max)
                               .catchAll(_ => ZIO.effect(lastPublished))

              _ <- ZIO.sleep(delay)

            } yield Option((newArticles, newPublished))

            elem
            // TODO: is there a better way?
              .provide(new Clock with Random {
                val clock  = clockService
                val random = randomService
              })
              .catchAll(e =>
                onGetFeedError(e) *>
                  ZIO.effect(Some((List.empty, lastPublished))),
              )
          }
    }
  }
}
