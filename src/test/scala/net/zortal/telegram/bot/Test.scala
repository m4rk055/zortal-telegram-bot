package net.zortal.telegram.bot

import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.environment.TestClock
import zio.duration.Duration
import net.zortal.telegram.bot.{ Help, Subscribe, Unsubscribe }
import sttp.client._
import sttp.client.testing._
import java.time.OffsetDateTime

case class SentMessage(msg: String, chatId: Long)

class TestTelegramService(sent: Ref[List[SentMessage]]) extends TelegramService {
  val telegramService = new TelegramService.Service[Any] {
    def sendMsg(msg: String, chatId: Long, parseMode: String = "Markdown") =
      sent.update(SentMessage(msg, chatId) :: _).unit

    def poll(offset: Long) = ???
  }
}

object Util {

  val zortalFeedEndpoint = uri"http://test-feed/feed"
  val telegramEndpoint   = uri"http://test-telegram/"
  val token              = "test"

  val telSendMsg = uri"$telegramEndpoint/bot$token/sendMessage"
  val telUpdates = uri"$telegramEndpoint/bot$token/getUpdates"

  def createTestHttpClient(
    sentBotResponses: Ref[List[SentMessage]],
    scenario: Ref[Int],
  ) = {
    val resp1 = scenario.get.map(scenario =>
      if (scenario == 0)
        Response.ok(
          """
          <feed>
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
          """,
        )
      else
        Response.ok(
          """
          <feed>
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
          """,
        ),
    )

    val resp2 = scenario.get.map { scenario =>
      val body =
        if (scenario == 0) """
          {
            "result": [{
              "update_id": 105,
              "message": {
                "date": 5,
                "text": "/help@bot",
                "chat": {"id": 4}
              }
            }, {
              "update_id": 104,
              "message": {
                "date": 4,
                "text": "/random@bot",
                "chat": {"id": 3}
              }
            }, {
              "update_id": 103,
              "message": {
                "date": 3,
                "text": "/daj@bot",
                "chat": {"id": 3}
              }
            }, {
              "update_id": 102
            }, {
              "update_id": 101,
              "message": {
                "date": 2,
                "text": "/daj@bot",
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
        else if (scenario == 1) """
            {
              "result": [{
                "update_id": 111,
                "message": {
                  "date": 11,
                  "text": "/stop@bot",
                  "chat": {"id": 3}
                }
              }, {
                "update_id": 110,
                "message": {
                  "date": 10,
                  "text": "/daj@bot",
                  "chat": {"id": 4}
                }
              }]
            }"""
        else if (scenario == 2) """
            {
              "result": [{
                "update_id": 120,
                "message": {
                  "date": 20,
                  "new_chat_member": {
                    "username": "bot"
                  },
                  "chat": {"id": 1}
                }
              }]
            }"""
        else if (scenario == 3) """
            {
              "result": [{
                "update_id": 120,
                "message": {
                  "date": 20,
                  "left_chat_member": {
                    "username": "bot"
                  },
                  "chat": {"id": 1}
                }
              }]
            }"""
        else """{}"""

      Response.ok(body)
    }

    SttpBackendStub[Task, Nothing](sttp.client.impl.zio.TaskMonadAsyncError)
      .whenRequestMatches(
        _.uri.toString.startsWith(zortalFeedEndpoint.toString),
      )
      .thenRespondWrapped(resp1)
      .whenRequestMatches(_.uri.toString.startsWith(telSendMsg.toString))
      .thenRespondWrapped { req =>
        val text   = req.uri.paramsMap("text")
        val chatId = req.uri.paramsMap("chat_id").toLong

        sentBotResponses.update(SentMessage(text, chatId) :: _) as Response.ok(
          "",
        )
      }
      .whenRequestMatches(_.uri.toString.startsWith(telUpdates.toString))
      .thenRespondWrapped(resp2)
      .whenRequestMatches(_ => true)
      .thenRespondNotFound()
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
            SentMessage("ЗОРТАЛ - [Title 1](http://zortal.org/1)", 1),
            SentMessage("ЗОРТАЛ - [Title 1](http://zortal.org/1)", 2),
          )

          for {
            _            <- TestClock.setDateTime(OffsetDateTime.parse("1970-01-01T00:15:00Z"))
            sentMessages <- Ref.make[List[SentMessage]](Nil)

            scenario       <- Ref.make[Int](0)
            testHttpClient = Util.createTestHttpClient(sentMessages, scenario)

            env <- ZIO.environment[ZEnv]
            now <- env.clock.currentDateTime

            testZortalFeedApi = ZortalFeedApi(
              env.clock,
              env.random,
              Util.zortalFeedEndpoint,
              now.toZonedDateTime,
              _ => ZIO.unit,
            )(testHttpClient).zortalFeedApi

            chatRepository <- ChatRepository.Dummy.make().map(_.chatRepository)
            _              <- chatRepository.saveChat(1)
            _              <- chatRepository.saveChat(2)

            telegramBot_ <- TelegramBot(
                             TelegramService(Util.telegramEndpoint, Util.token)(testHttpClient).telegramService,
                             chatRepository,
                             "bot",
                             _ => ZIO.unit,
                           ).map(_.telegramBot)

            _ <- ZortalFeedApi.>.getFeed(Duration.Zero)
                  .provide(
                    new ZortalFeedApi {
                      val zortalFeedApi = testZortalFeedApi
                    },
                  )
                  .take(2)
                  .foreach(articles =>
                    scenario.update(_ + 1) *> telegramBot_.sendArticles(articles),
                  )

            sent <- sentMessages.get

          } yield {
            assert(sent.length, equalTo(responses.length)) &&
            assert(sent.toSet, equalTo(responses.toSet))
          }
        },
        testM("handle telegram messages") {
          val responses1 = List(
            SentMessage(Subscribe.responseMsg, 2),
            SentMessage(Subscribe.responseMsg, 3),
            SentMessage(Help.responseMsg, 4),
          )

          val responses2 = List(
            SentMessage(Unsubscribe.responseMsg, 3),
            SentMessage(Subscribe.responseMsg, 4),
          )

          val responses3 = List(
            SentMessage(Subscribe.responseMsg, 1),
          )

          for {
            sentMessages <- Ref.make[List[SentMessage]](Nil)
            scenario     <- Ref.make[Int](0)

            testHttpClient = Util.createTestHttpClient(sentMessages, scenario)

            chatRepository <- ChatRepository.Dummy.make().map(_.chatRepository)

            telegramBot_ <- TelegramBot(
                             TelegramService(Util.telegramEndpoint, Util.token)(testHttpClient).telegramService,
                             chatRepository,
                             "bot",
                             _ => ZIO.unit,
                           ).map(_.telegramBot)

            program = TelegramBot.>.handleMessages
              .provide(
                new TelegramBot {
                  val telegramBot = telegramBot_
                },
              )
              .runHead

            result1 <- program

            sent1 <- sentMessages.get

            _ <- scenario.update(_ + 1)
            _ <- sentMessages.set(Nil)

            result2 <- program

            sent2 <- sentMessages.get

            _ <- scenario.update(_ + 1)
            _ <- sentMessages.set(Nil)

            result3 <- program

            sent3 <- sentMessages.get

            _ <- scenario.update(_ + 1)

            result4 <- program

          } yield {
            assert(result1, equalTo(Some(Result(Set(2, 3), Set.empty)))) &&
            assert(sent1.toSet, equalTo(responses1.toSet)) &&
            assert(result2, equalTo(Some(Result(Set(4), Set(3))))) &&
            assert(sent2.toSet, equalTo(responses2.toSet)) &&
            assert(result3, equalTo(Some(Result(Set(1), Set.empty)))) &&
            assert(sent3.toSet, equalTo(responses3.toSet)) &&
            assert(result4, equalTo(Some(Result(Set.empty, Set(1)))))
          }
        },
      ),
    )
