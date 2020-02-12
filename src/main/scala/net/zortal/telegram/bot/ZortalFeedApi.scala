package net.zortal.telegram.bot

import zio._
import zio.stream.Stream
import zio.duration.Duration
import zio.clock.Clock
import zio.random.Random
import zio.stream.ZStream
import net.zortal.telegram.bot.Article
import java.time.ZonedDateTime
import java.time.ZonedDateTime
import org.jsoup.Jsoup
import scala.xml._
import sttp.model.Uri
import sttp.client._

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
    endPoint: Uri,
    startTime: ZonedDateTime,
    onGetFeedError: Throwable => Task[Unit],
  )(implicit httpClient: SttpBackend[Task, Nothing, NothingT]) = new ZortalFeedApi {

    val zortalFeedApi = new ZortalFeedApi.Service[Any] {

      def getFeed(delay: Duration) =
        Stream
          .unfoldM[Throwable, List[Article], ZonedDateTime](startTime) { lastPublished =>
            val elem = for {
              resp <- quickRequest
                       .get(endPoint)
                       .send()
                       .retry(exponentialBackoffStrategy)

              xml <- ZIO.effect(XML.loadString(resp.body))
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

            } yield Option((newArticles, newPublished))

            elem
              .catchAll(e =>
                onGetFeedError(e) *>
                  ZIO.effect(Some((List.empty, lastPublished))),
              )
              .zipLeft(ZIO.sleep(delay))
              .provide(new Clock with Random {
                val clock  = clockService
                val random = randomService
              })
          }
    }
  }
}
