package net.zortal.telegram.bot

import zio._
import zio.interop.catz._
import io.circe._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client

// TODO: better model
case class Chat(id: Long)
case class Message(text: Option[String], chat: Chat, date: Long)
case class Update(updateId: Long, message: Option[Message])
case class PollResponse(results: List[Update])

trait TelegramService {
  val telegramService: TelegramService.Service[Any]
}

object TelegramService {

  trait Service[R] {
    def sendMsg(msg: String, chatId: Long, parseMode: String = "Markdown"): ZIO[R, Throwable, Unit]

    def poll(offset: Long): ZIO[R, Throwable, PollResponse]
  }

  def apply(
    httpClient: Client[Task],
    endPoint: Uri,
    token: String,
  ) = new TelegramService {

    import Decoders._

    val telegramService = new TelegramService.Service[Any] {

      def sendMsg(
        msg: String,
        chatId: Long,
        parseMode: String,
      ): ZIO[Any, Throwable, Unit] = {

        val req = endPoint / s"bot$token" / "sendMessage" =? Map(
          "chat_id"    -> List(chatId.toString),
          "text"       -> List(msg),
          "parse_mode" -> List(parseMode),
        )

        httpClient.expect[Unit](req)
      }

      def poll(offset: Long) = {
        val req = endPoint / s"bot$token" / "getUpdates" =? Map(
          "offset"          -> List((offset + 1).toString),
          "timeout"         -> List("5"),
          "allowed_updates" -> List("""["message"]"""),
        )

        httpClient.expect[PollResponse](req)
      }
    }
  }
}

object Decoders {

  implicit val d1: Decoder[Chat] =
    Decoder.forProduct1("id")(Chat.apply)

  implicit def ed1: EntityDecoder[Task, Chat] =
    jsonOf[Task, Chat]

  implicit val d2: Decoder[Message] =
    Decoder.forProduct3("text", "chat", "date")(Message.apply)

  implicit def ed2: EntityDecoder[Task, Message] =
    jsonOf[Task, Message]

  implicit val d3: Decoder[Update] =
    Decoder.forProduct2("update_id", "message")(Update.apply)

  implicit def ed3: EntityDecoder[Task, Update] =
    jsonOf[Task, Update]

  implicit val d4: Decoder[PollResponse] =
    Decoder.forProduct1("result")(PollResponse.apply)

  implicit def ed4: EntityDecoder[Task, PollResponse] =
    jsonOf[Task, PollResponse]
}
