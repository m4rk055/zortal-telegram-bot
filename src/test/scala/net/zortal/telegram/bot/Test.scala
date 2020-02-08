package net.zortal.telegram.bot

import zio.interop.catz._
import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.environment.TestClock
import fs2.{ Stream }
import fs2.text.utf8Encode
import java.time.{ OffsetDateTime, ZonedDateTime }
import org.http4s._
import org.http4s.client.Client
import cats.effect.Resource
import net.zortal.telegram.bot.Unknown
import net.zortal.telegram.bot.Subscribe
import net.zortal.telegram.bot.Unsubscribe

case class SentMessage(msg: String, chatId: Long)

class TestTelegramService(sent: Ref[List[SentMessage]]) extends TelegramService {
  val telegramService = new TelegramService.Service[Any] {
    def sendMsg(msg: String, chatId: Long, parseMode: String = "Markdown") =
      sent.update(SentMessage(msg, chatId) :: _).unit

    def poll(offset: Long) = ???
  }
}

object Util {

  val zortalFeedEndpoint = Uri.unsafeFromString("http://test-feed/feed")
  val telegramEndpoint   = Uri.unsafeFromString("http://test-telegram/")
  val token              = "test"

  def createTestHttpClient(
    sentBotResponses: Ref[List[SentMessage]],
    scenario: Ref[Int],
  ) =
    Client[Task] { req =>
      val telSendMsg = telegramEndpoint / s"bot$token" / "sendMessage"
      val telUpdates = telegramEndpoint / s"bot$token" / "getUpdates"

      val resp: Task[Response[Task]] = req.uri.toString match {
        case s if s.startsWith(zortalFeedEndpoint.toString) =>
          Task.effect(
            Response[Task](
              status = Status.Ok,
              body = Stream("""
                      <feed>
                        <updated>2020-01-01T13:00:00Z</updated>
                        <entry>
                          <title type="html"><![CDATA[Title 3]]></title>
                          <published>2020-01-01T13:00:00Z</published>
                          <link href="http://zortal.org/3" />
                        </entry>
                        <entry>
                          <title type="html"><![CDATA[Title 2]]></title>
                          <published>2020-01-01T12:30:00Z</published>
                          <link href="http://zortal.org/2" />
                        </entry>
                        <entry>
                          <title type="html"><![CDATA[Title 1]]></title>
                          <published>2020-01-01T12:00:00Z</published>
                          <link href="http://zortal.org/1" />
                        </entry>
                      </feed>
                    """).through(utf8Encode),
            ),
          )
        case s if s.startsWith(telSendMsg.toString) => {
          val text   = req.uri.query.params("text")
          val chatId = req.uri.query.params("chat_id").toLong

          sentBotResponses.update(SentMessage(text, chatId) :: _) as Response(Status.Ok)
        }
        case s if s.startsWith(telUpdates.toString) => {
          scenario.get.map { x =>
            val body =
              if (x == 0) """
              {
                "result": [{
                  "update_id": 104,
                  "message": {
                    "date": 4,
                    "text": "/random",
                    "chat": {"id": 4}
                  }
                }, {
                  "update_id": 103,
                  "message": {
                    "date": 3,
                    "text": "/daj",
                    "chat": {"id": 3}
                  }
                }, {
                  "update_id": 102
                }, {
                  "update_id": 101,
                  "message": {
                    "date": 2,
                    "text": "/daj",
                    "chat": {"id": 2}
                  }
                }, {
                  "update_id": 100,
                  "message": {
                    "date": 1,
                    "chat": {"id": 1}
                  }
                }]
              }"""
              else if (x == 1) """
                {
                  "result": [{
                    "update_id": 111,
                    "message": {
                      "date": 11,
                      "text": "/stop",
                      "chat": {"id": 3}
                    }
                  }, {
                    "update_id": 110,
                    "message": {
                      "date": 10,
                      "text": "/daj",
                      "chat": {"id": 4}
                    }
                  }]
                }"""
              else """{}"""

            Response[Task](status = Status.Ok).withEntity(body)
          }
        }
        case _ => Task.effect(Response.notFound[Task])
      }

      Resource.make(resp)(_ => Task.unit)
    }
}

object Test
    extends DefaultRunnableSpec(
      suite("test")(
        testM("parse feed and send send messages to telegram") {
          val responses = List(
            SentMessage("ЗОРТАЛ - [Title 3](http://zortal.org/3)", 1),
            SentMessage("ЗОРТАЛ - [Title 3](http://zortal.org/3)", 2),
            SentMessage("ЗОРТАЛ - [Title 2](http://zortal.org/2)", 1),
            SentMessage("ЗОРТАЛ - [Title 2](http://zortal.org/2)", 2),
          )

          for {
            _            <- TestClock.setDateTime(OffsetDateTime.parse("1970-01-01T00:15:00Z"))
            state        <- Ref.make(State(ZonedDateTime.parse("2020-01-01T12:15:00Z")))
            sentMessages <- Ref.make[List[SentMessage]](Nil)

            scenario       <- Ref.make[Int](0)
            testHttpClient = Util.createTestHttpClient(sentMessages, scenario)

            program = Main.reqFeed(state)

            zortalFeed     = ZortalFeedApi(testHttpClient, Util.zortalFeedEndpoint)
            chatRepository <- ChatRepository.Dummy.make().map(_.chatRepository)
            _              <- chatRepository.saveChat(1)
            _              <- chatRepository.saveChat(2)

            telegramBot_ <- TelegramBot(
                             TelegramService(testHttpClient, Util.telegramEndpoint, Util.token).telegramService,
                             chatRepository,
                           ).map(_.telegramBot)

            _ <- program.provide(new ZortalFeedApi with TelegramBot {
                  val zortalFeedApi = zortalFeed.zortalFeedApi
                  val telegramBot   = telegramBot_
                })

            sent     <- sentMessages.get
            newState <- state.get

          } yield {
            assert(sent.length, equalTo(responses.length)) &&
            assert(sent.toSet, equalTo(responses.toSet)) &&
            assert(newState.lastCheck, equalTo(ZonedDateTime.parse("2020-01-01T13:00:00Z")))
          }
        },
        testM("handle telegram messages") {
          val responses1 = List(
            SentMessage(Unknown.responseMsg, 1),
            SentMessage(Subscribe.responseMsg, 2),
            SentMessage(Subscribe.responseMsg, 3),
            SentMessage(Unknown.responseMsg, 4),
          )

          val responses2 = List(
            SentMessage(Unsubscribe.responseMsg, 3),
            SentMessage(Subscribe.responseMsg, 4),
          )

          for {
            sentMessages <- Ref.make[List[SentMessage]](Nil)
            scenario     <- Ref.make[Int](0)

            testHttpClient = Util.createTestHttpClient(sentMessages, scenario)

            chatRepository <- ChatRepository.Dummy.make().map(_.chatRepository)

            telegramBot_ <- TelegramBot(
                             TelegramService(testHttpClient, Util.telegramEndpoint, Util.token).telegramService,
                             chatRepository,
                           ).map(_.telegramBot)

            s <- TelegramBot.>.handleMessages(_ => ???).provide(
                  new TelegramBot {
                    val telegramBot = telegramBot_
                  },
                )
            result1 <- s.runHead

            sent1 <- sentMessages.get

            _ <- scenario.update(_ + 1)
            _ <- sentMessages.set(Nil)

            s <- TelegramBot.>.handleMessages(_ => ???).provide(
                  new TelegramBot {
                    val telegramBot = telegramBot_
                  },
                )
            result2 <- s.runHead

            sent2 <- sentMessages.get

          } yield {
            assert(result1, equalTo(Some(Result(Set(2, 3), Set.empty)))) &&
            assert(sent1.toSet, equalTo(responses1.toSet)) &&
            assert(result2, equalTo(Some(Result(Set(4), Set(3))))) &&
            assert(sent2.toSet, equalTo(responses2.toSet))
          }
        },
      ),
    )
