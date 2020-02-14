package net.zortal.telegram.bot

import zio._
import zio.console._
import zio.system._
import zio.clock.{ Clock => ZClock }
import zio.internal.{ Platform, PlatformLive }
import zio.duration.Duration
import sttp.model.Uri
import sttp.client.asynchttpclient.zio.AsyncHttpClientZioBackend
import java.time.ZonedDateTime
import net.zortal.telegram.bot.{ TelegramBot, ZortalFeedApi }
import pureconfig._

case class Article(published: ZonedDateTime, title: String, link: String)

object Main extends App {

  override val platform: Platform =
    PlatformLive.Default.withReportFailure(_ => ())

  def handleZortalFeedUpdates(feedCheckDelay: Duration) =
    ZortalFeedApi.>.getFeed(feedCheckDelay).foreach(newArticles =>
      if (newArticles.length > 0)
        TelegramBot.>.sendArticles(newArticles.take(2))
      else ZIO.unit,
    )

  def handleTelegramMessages =
    TelegramBot.>.handleMessages.foreach(result =>
      ZIO.foreach(result.subscriptions)(ChatRepository.>.saveChat) *>
        ZIO.foreach(result.unsubscriptions)(ChatRepository.>.removeChat),
    )

  def program(feedCheckDelay: Duration) =
    for {
      _ <- handleTelegramMessages.fork
      _ <- handleZortalFeedUpdates(feedCheckDelay).fork
    } yield ()

  def getUri(uri: String) =
    ZIO.fromEither(Uri.parse(uri)).mapError(new Throwable(_))

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    Managed
      .make(AsyncHttpClientZioBackend())(_.close().orDie)
      .use { implicit client =>
        for {
          _ <- putStrLn("START")

          config <- ZIO
                     .fromEither(ConfigSource.default.load[Config])
                     .catchAll(_ => ZIO.fail(new Throwable("Error loading config.")))

          zortalFeedUrl <- getUri(config.zortal.feedUrl)
          telegramUrl   <- getUri(config.telegram.url)

          firestoreChatRepository <- FirestoreChatRepository
                                      .make(
                                        platform.executor,
                                        config.firestore.projectId,
                                        config.firestore.chatsCollection,
                                      )
                                      .map(_.chatRepository)

          env        <- ZIO.environment[ZEnv]
          envConsole <- ZIO.environment[Console]
          now        <- clock.currentDateTime

          liveZortalFeedApi = ZortalFeedApi(
            env.clock,
            env.random,
            zortalFeedUrl,
            now.toZonedDateTime,
            _ => putStrLn("Some error with polling").provide(envConsole),
          ).zortalFeedApi

          liveTelegramService = TelegramService(telegramUrl, config.telegram.token)
          liveTelegramBot <- TelegramBot(
                              liveTelegramService.telegramService,
                              firestoreChatRepository,
                              config.bot.id,
                              _ => putStrLn("Some error with polling").provide(envConsole),
                            ).map(_.telegramBot)

          _ <- program(config.zortal.delay.seconds).provide(
                new ZortalFeedApi
                  with TelegramBot
                  with ChatRepository
                  with ZClock.Live
                  with Console.Live
                  with System.Live {
                  val zortalFeedApi  = liveZortalFeedApi
                  val telegramBot    = liveTelegramBot
                  val chatRepository = firestoreChatRepository
                },
              )

          _ <- ZIO.never
        } yield ()
      }
      .orDie as 0
}
