package net.zortal.telegram.bot

import cats.effect._
import zio._
import zio.console._
import zio.interop.catz._
import zio.system
import zio.system._
import zio.clock.{ Clock => ZClock }
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s._
import scala.concurrent.ExecutionContext.global
import java.time.ZonedDateTime
import zio.internal.{ Platform, PlatformLive }
import net.zortal.telegram.bot.{ TelegramBot, ZortalFeedApi }
import zio.duration.Duration

case class Article(published: ZonedDateTime, title: String, link: String)

case class Config(
  zortalFeedEndpoint: String,
  telegramEndpoint: String,
  feedCheckDelaySeconds: Int,
)

object Main extends App {

  override val platform: Platform = PlatformLive.Default.withReportFailure(_ => ())

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

  val httpClientRes = for {
    implicit0(ce: ConcurrentEffect[Task]) <- ZManaged.fromEffect(
                                              ZIO.concurrentEffect[Any],
                                            )
    client <- BlazeClientBuilder[Task](global).resource.toManaged
  } yield client

  // TODO: to config
  val conf = Config(
    "https://zortal.net/index.php/feed/atom/",
    "https://api.telegram.org",
    30,
  )

  def getEnv(variableName: String) =
    system
      .env(variableName)
      .flatMap {
        case Some(v) =>
          ZIO.effect(v)
        case None =>
          ZIO.fail(
            new Throwable(s"$variableName env variable missing"),
          )
      }

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    httpClientRes
      .use(httpClient =>
        for {
          _ <- putStrLn("START")

          zortalUri        <- ZIO.fromEither(Uri.fromString(conf.zortalFeedEndpoint))
          telegramUri      <- ZIO.fromEither(Uri.fromString(conf.telegramEndpoint))
          telegramBotToken <- getEnv("ZORTAL_TOKEN")
          botName          <- getEnv("ZORTAL_BOT_NAME")

          inMemChatRepository <- ChatRepository.Dummy.make().map(_.chatRepository)

          env        <- ZIO.environment[ZEnv]
          envConsole <- ZIO.environment[Console]
          now        <- clock.currentDateTime

          liveZortalFeedApi = ZortalFeedApi(
            env.clock,
            env.random,
            httpClient,
            zortalUri,
            now.toZonedDateTime,
            _ => putStrLn("Some error with polling").provide(envConsole),
          ).zortalFeedApi

          liveTelegramService = TelegramService(httpClient, telegramUri, telegramBotToken)
          liveTelegramBot <- TelegramBot(
                              liveTelegramService.telegramService,
                              inMemChatRepository,
                              botName,
                              _ => putStrLn("Some error with polling").provide(envConsole),
                            ).map(_.telegramBot)

          _ <- program(conf.feedCheckDelaySeconds.seconds).provide(
                new ZortalFeedApi
                  with TelegramBot
                  with ChatRepository
                  with ZClock.Live
                  with Console.Live
                  with System.Live {
                  val zortalFeedApi  = liveZortalFeedApi
                  val telegramBot    = liveTelegramBot
                  val chatRepository = inMemChatRepository
                },
              )

          _ <- ZIO.never
        } yield (),
      )
      .orDie as 0

}
