package net.zortal.telegram.bot

import zio._
import zio.stream.Stream
import net.zortal.telegram.bot.TelegramService
import net.zortal.telegram.bot.Message
import java.util.concurrent.TimeUnit

case class Result(
  subscriptions: Set[Long],
  unsubscriptions: Set[Long],
)

// TODO: strongly type in telegram message
sealed trait Command {
  val responseMsg = this match {
    case Subscribe   => "Успешно сте се претплатили на Зортал!"
    case Unsubscribe => "АЦО СРБИНЕ"
    case Unknown     => "/daj - претплата на Зортал \n/stop - :("
  }
}
case object Subscribe   extends Command
case object Unsubscribe extends Command
case object Unknown     extends Command

trait TelegramBot {
  val telegramBot: TelegramBot.Service[Any]
}

object TelegramBot {

  trait Service[R] {
    def sendArticles(articles: List[Article]): ZIO[R, Throwable, Unit]

    def handleMessages(
      onError: Throwable => Task[Unit],
    ): ZIO[R, Throwable, Stream[Throwable, Result]]
  }

  object > {
    def sendArticles(articles: List[Article]) =
      ZIO.accessM[TelegramBot](_.telegramBot.sendArticles(articles))

    def handleMessages(onError: Throwable => Task[Unit] = _ => Task.unit) =
      ZIO.accessM[TelegramBot](_.telegramBot.handleMessages(onError))
  }

  def apply(
    telegramService: TelegramService.Service[Any],
    chatRepository: ChatRepository.Service[Any],
  ) =
    for {
      offset    <- Ref.make[Long](-1)
      startTime <- clock.currentTime(TimeUnit.SECONDS)
    } yield new TelegramBot {

      val telegramBot = new TelegramBot.Service[Any] {

        def sendArticles(articles: List[Article]): ZIO[Any, Throwable, Unit] =
          chatRepository.getChatIds.flatMap(chatIds =>
            ZIO.foreachPar_(chatIds)(chatId =>
              ZIO.foreach_(articles.sortBy(_.published))(article =>
                telegramService
                  .sendMsg(s"ЗОРТАЛ - [${article.title}](${article.link})", chatId),
              ),
            ),
          )

        def handleMessages(
          onError: Throwable => Task[Unit],
        ): ZIO[Any, Throwable, Stream[Throwable, Result]] = {

          val stream = Stream
            .unfoldM[Throwable, Result, Unit](()) { _ =>
              val elem =
                for {
                  result <- Ref.make(Result(Set.empty, Set.empty))
                  offs   <- offset.get
                  resp   <- telegramService.poll(offs)

                  _ <- ZIO
                        .effect(resp.results.map(_.updateId).max)
                        .foldM(
                          _ => ZIO.unit,
                          maxOffset =>
                            offset.set(maxOffset) *> ZIO.foreach_(
                              resp.results.flatMap(_.message).filter(_.date >= startTime),
                            )(msg => handleMessage(msg, result)),
                        )
                  res <- result.get
                } yield Option((res, ()))

              elem.catchAll(e => onError(e) *> ZIO.effect(Some(Result(Set.empty, Set.empty), ()))),
            }

          ZIO.effect(stream)
        }

        private def handleMessage(msg: Message, result: Ref[Result]) =
          msg.text match {
            case Some("/daj") =>
              result.update(r => r.copy(subscriptions = r.subscriptions + msg.chat.id)) *>
                telegramService.sendMsg(Subscribe.responseMsg, msg.chat.id)
            case Some("/stop") =>
              result.update(r => r.copy(unsubscriptions = r.unsubscriptions + msg.chat.id)) *>
                telegramService.sendMsg(Unsubscribe.responseMsg, msg.chat.id)
            case _ =>
              telegramService.sendMsg(Unknown.responseMsg, msg.chat.id)
          }

      }
    },
}
