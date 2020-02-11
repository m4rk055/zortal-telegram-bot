package net.zortal.telegram.bot

import zio._
import zio.interop.catz._
import io.circe._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client

// TODO: better model
case class Chat(id: Long)
case class NewMember(username: String)
case class LeftMember(username: String)
case class Message(
  text: Option[String],
  newMember: Option[NewMember],
  leftMember: Option[LeftMember],
  chat: Chat,
  date: Long,
)
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
          "offset"  -> List((offset + 1).toString),
          "timeout" -> List("30"),
        )

        httpClient.expect[PollResponse](req)
      }
    }
  }
}

object Decoders {

  implicit val chatDecoder =
    Decoder.forProduct1("id")(Chat.apply)

  implicit def chatEDecoder =
    jsonOf[Task, Chat]

  implicit val newMemberDecoder =
    Decoder.forProduct1("username")(NewMember.apply)

  implicit def newMemberEDecoder =
    jsonOf[Task, NewMember]

  implicit val leftMemberDecoder =
    Decoder.forProduct1("username")(LeftMember.apply)

  implicit def leftMemberEDecoder =
    jsonOf[Task, LeftMember]

  implicit val messageDecoder =
    Decoder.forProduct5("text", "new_chat_member", "left_chat_member", "chat", "date")(
      Message.apply,
    )

  implicit def messageEDecoder =
    jsonOf[Task, Message]

  implicit val updateDecoder =
    Decoder.forProduct2("update_id", "message")(Update.apply)

  implicit def updateEDecoder =
    jsonOf[Task, Update]

  implicit val pollRespDecoder =
    Decoder.forProduct1("result")(PollResponse.apply)

  implicit def pollRespEDecode: EntityDecoder[Task, PollResponse] =
    jsonOf[Task, PollResponse]
}
