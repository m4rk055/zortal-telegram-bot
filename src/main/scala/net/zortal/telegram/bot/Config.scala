package net.zortal.telegram.bot

import pureconfig.ConfigConvert
import pureconfig._
import pureconfig.generic.semiauto._

final case class Config(
  zortal: ZortalConfig,
  telegram: TelegramConfig,
  firestore: FirestoreConfig,
  bot: BotConfig,
)

object Config {
  implicit val convert: ConfigConvert[Config] = deriveConvert
}

final case class ZortalConfig(feedUrl: String, delay: Int)

object ZortalConfig {
  implicit val convert: ConfigConvert[ZortalConfig] = deriveConvert
}

final case class TelegramConfig(url: String, token: String)

object TelegramConfig {
  implicit val convert: ConfigConvert[TelegramConfig] = deriveConvert
}

final case class FirestoreConfig(projectId: String, chatsCollection: String)

object FirestoreConfig {
  implicit val convert: ConfigConvert[FirestoreConfig] = deriveConvert
}

final case class BotConfig(id: String)

object BotConfig {
  implicit val convert: ConfigConvert[BotConfig] = deriveConvert
}
