package net.zortal.telegram.bot

import zio._
import zio.stream.{ Stream, ZStream }
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
    case Help        => "/daj - Претплата на Зортал \n/stop - Одјава претплате са Зортала"
  }
}
case object Subscribe   extends Command
case object Unsubscribe extends Command
case object Help        extends Command

trait TelegramBot {
  val telegramBot: TelegramBot.Service[Any]
}

object TelegramBot {

  trait Service[R] {
    def sendArticles(articles: List[Article]): ZIO[R, Throwable, Unit]

    def handleMessages: Stream[Throwable, Result]
  }

  object > {
    def sendArticles(articles: List[Article]) =
      ZIO.accessM[TelegramBot](_.telegramBot.sendArticles(articles))

    def handleMessages =
      ZStream.unwrap(ZIO.access[TelegramBot](_.telegramBot.handleMessages))
  }

  def apply(
    telegramService: TelegramService.Service[Any],
    chatRepository: ChatRepository.Service[Any],
    name: String,
    onHandleMessageError: Throwable => Task[Unit],
  ) =
    for {
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

        def handleMessages =
          Stream
            .unfoldM[Throwable, Result, Long](-1) { offset =>
              val elem =
                for {
                  result <- Ref.make(Result(Set.empty, Set.empty))
                  resp   <- telegramService.poll(offset)

                  newOffset <- ZIO
                                .effect(resp.results.map(_.updateId).max)
                                .zipLeft(
                                  ZIO.foreach_(
                                    resp.results.flatMap(_.message).filter(_.date >= startTime),
                                  )(msg => handleMessage(msg, result)),
                                )
                                .catchAll(_ => ZIO.effect(offset))

                  res <- result.get
                } yield Option((res, newOffset))

              elem.catchAll(e =>
                onHandleMessageError(e) *> ZIO.effect(Some(Result(Set.empty, Set.empty), offset)),
              ),
            }

        private def handleMessage(msg: Message, result: Ref[Result]) =
          msg match {
            case Message(Some(s"/daj@${`name` }"), _, _, Chat(id), _) =>
              result.update(r => r.copy(subscriptions = r.subscriptions + id)) *>
                telegramService.sendMsg(Subscribe.responseMsg, id)

            case Message(Some(s"/stop@${`name` }"), _, _, Chat(id), _) =>
              result.update(r => r.copy(unsubscriptions = r.unsubscriptions + id)) *>
                telegramService.sendMsg(Unsubscribe.responseMsg, id)

            case Message(Some(s"/help@${`name` }"), _, _, Chat(id), _) =>
              telegramService.sendMsg(Help.responseMsg, id)

            case Message(_, Some(NewMember(`name`)), _, Chat(id), _) =>
              result.update(r => r.copy(subscriptions = r.subscriptions + id)) *>
                telegramService.sendMsg(Subscribe.responseMsg, id)

            case Message(_, _, Some(LeftMember(`name`)), Chat(id), _) =>
              result.update(r => r.copy(unsubscriptions = r.unsubscriptions + id))

            case _ => ZIO.unit
          }
      }
    },
}
