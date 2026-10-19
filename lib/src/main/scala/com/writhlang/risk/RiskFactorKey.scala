package com.writhlang.risk

/**
  * A time dimension measured in years. Used for tenor, expiry and swap-tenor
  * coordinates of a [[RiskFactorKey]]. Canonical form appends `Y`, e.g. `5Y`.
  *
  * Example: `Tenor(5.0).canonical == "5Y"`.
  */
final case class Tenor(years: Double) {
  def canonical: String = Tenor.renderYears(years) + "Y"

  /**
    * The calendar date `years` after `asOf`: whole years by year, anything else
    * by whole months (`0.25` → +3 months, `1.5` → +18 months). The engine's time
    * axis is year fractions derived from dates, so a quoted tenor is resolved to
    * a date before it can be bootstrapped.
    */
  def resolve(asOf: java.time.LocalDate): java.time.LocalDate =
    if (years == years.toLong.toDouble) asOf.plusYears(years.toLong)
    else asOf.plusMonths(math.round(years * 12.0))
}

object Tenor {
  /** Render whole years without a trailing `.0` (`5.0` -> `"5"`, `0.5` -> `"0.5"`). */
  def renderYears(years: Double): String =
    if (years == years.toLong.toDouble) years.toLong.toString else years.toString

  private val YearRe  = "^([0-9]+(?:\\.[0-9]+)?)Y$".r
  private val MonthRe = "^([0-9]+(?:\\.[0-9]+)?)M$".r

  /** Parse a tenor such as `"2Y"` (years) or `"3M"` (months, converted to years). */
  def parse(segment: String): Either[String, Tenor] = segment match {
    case YearRe(years)    => Right(Tenor(years.toDouble))
    case MonthRe(months)  => Right(Tenor(months.toDouble / 12.0))
    case _                => Left(s"invalid tenor: $segment (expected e.g. 3M, 2Y)")
  }

  implicit final class YearsDoubleSyntax(private val years: Double) extends AnyVal {
    def Y: Tenor = Tenor(years)
  }
  implicit final class YearsIntSyntax(private val years: Int) extends AnyVal {
    def Y: Tenor = Tenor(years.toDouble)
  }
}

/**
  * A strike coordinate for an option/volatility key. `ATM` is the canonical
  * "at-the-money" marker; [[StrikeValue]] is an explicit rate strike.
  */
sealed trait Strike { def canonical: String }

case object ATM extends Strike { val canonical = "ATM" }

final case class StrikeValue(rate: Double) extends Strike {
  def canonical: String = RiskFactorKey.renderDecimal(rate)
}

/** A single typed coordinate of a [[RiskFactorKey]]. */
sealed trait Dimension { def canonical: String }

final case class TenorDim(years: Double) extends Dimension {
  def canonical: String = Tenor.renderYears(years) + "Y"
}
final case class ExpiryDim(years: Double) extends Dimension {
  def canonical: String = Tenor.renderYears(years) + "Y"
}
final case class SwapTenorDim(years: Double) extends Dimension {
  def canonical: String = Tenor.renderYears(years) + "Y"
}
final case class StrikeDim(strike: Strike) extends Dimension {
  def canonical: String = strike.canonical
}
final case class LossLevelDim(level: Double) extends Dimension {
  def canonical: String = RiskFactorKey.renderDecimal(level)
}

/**
  * A fully-addressed risk factor: a [[KeyType]] plus a name (typically a
  * currency or, for `FxSpot`, a currency pair) plus optional dimensions such as
  * tenor, expiry, swap tenor, strike, or loss level.
  *
  * Two keys are equal iff their canonical strings are equal, which is what makes
  * them safe to embed in DAG node ids and use as map keys.
  */
final case class RiskFactorKey(
  keyType: KeyType,
  name: String,
  tenor: Option[Tenor] = None,
  expiry: Option[Tenor] = None,
  swapTenor: Option[Tenor] = None,
  strike: Option[Strike] = None,
  lossLevel: Option[Double] = None
) {
  /** Present dimensions in canonical order. */
  def dimensions: Vector[Dimension] =
    Vector(
      tenor.map(t => TenorDim(t.years)),
      expiry.map(e => ExpiryDim(e.years)),
      swapTenor.map(s => SwapTenorDim(s.years)),
      strike.map(StrikeDim),
      lossLevel.map(LossLevelDim)
    ).flatten

  /** The unique, round-trippable string form `KeyType:name[:dim...]`. */
  def canonical: String = {
    val dimParts: List[String] = keyType match {
      case KeyType.SwaptionVolatility =>
        val expirySwap = for {
          e <- expiry
          s <- swapTenor
        } yield s"${e.canonical}x${s.canonical}"
        expirySwap.toList ++ strike.toList.map(_.canonical) ++ lossLevel.toList.map(RiskFactorKey.renderDecimal)
      case _ =>
        dimensions.map(_.canonical).toList
    }
    (s"${keyType.name}:$name" :: dimParts).mkString(":")
  }

  /** The same key without its tenor dimension (the curve-level key). */
  def withoutTenor: RiskFactorKey = copy(tenor = None)
}

object RiskFactorKey {
  /** Render a decimal without a trailing `.0` (`0.03` -> `"0.03"`, `1.0` -> `"1"`). */
  def renderDecimal(value: Double): String =
    if (value == value.toLong.toDouble) value.toLong.toString else value.toString

  /**
    * Parse a canonical key string back into a [[RiskFactorKey]].
    *
    * Supported forms:
    *   - `KeyType:name`                       (no dimensions)
    *   - `DiscountCurve:CCY:5Y`               (single tenor)
    *   - `SwaptionVolatility:CCY:5Yx10Y:ATM`  (expiry x swap tenor + strike)
    */
  def parse(canonical: String): Either[String, RiskFactorKey] = {
    val parts = canonical.split(":", -1).toList
    parts match {
      case keyTypeName :: name :: rest =>
        KeyType.fromName(keyTypeName) match {
          case None          => Left(s"unknown risk factor key type: $keyTypeName")
          case Some(keyType) => parseDimensions(keyType, name, rest)
        }
      case _ => Left(s"invalid risk factor key: $canonical")
    }
  }

  private def parseDimensions(keyType: KeyType, name: String, rest: List[String]): Either[String, RiskFactorKey] =
    keyType match {
      case KeyType.SwaptionVolatility =>
        rest match {
          case Nil => Right(RiskFactorKey(keyType, name))
          case List(expirySwap) =>
            parseExpirySwap(expirySwap).map { case (expiry, swapTenor) =>
              RiskFactorKey(keyType, name, expiry = Some(expiry), swapTenor = Some(swapTenor))
            }
          case List(expirySwap, strikeStr) =>
            for {
              es <- parseExpirySwap(expirySwap)
              st <- parseStrike(strikeStr)
            } yield RiskFactorKey(keyType, name, expiry = Some(es._1), swapTenor = Some(es._2), strike = Some(st))
          case _ => Left(s"too many dimensions for SwaptionVolatility key: ${rest.mkString(":")}")
        }
      case KeyType.DiscountCurve | KeyType.IndexCurve | KeyType.YieldCurve | KeyType.CreditCurve =>
        rest match {
          case Nil => Right(RiskFactorKey(keyType, name))
          case List(tenorStr) =>
            Tenor.parse(tenorStr).map(t => RiskFactorKey(keyType, name, tenor = Some(t)))
          case _ => Left(s"too many dimensions for ${keyType.name} key: ${rest.mkString(":")}")
        }
      case _ =>
        if (rest.isEmpty) Right(RiskFactorKey(keyType, name))
        else Left(s"dimensions not supported for ${keyType.name} key: ${rest.mkString(":")}")
    }

  private val ExpirySwapRe = "^([0-9]+(?:\\.[0-9]+)?)Yx([0-9]+(?:\\.[0-9]+)?)Y$".r

  private def parseExpirySwap(segment: String): Either[String, (Tenor, Tenor)] = segment match {
    case ExpirySwapRe(expiry, swapTenor) => Right((Tenor(expiry.toDouble), Tenor(swapTenor.toDouble)))
    case _                               => Left(s"invalid expiry x swap tenor dimension: $segment")
  }

  private def parseStrike(segment: String): Either[String, Strike] = segment match {
    case "ATM" => Right(ATM)
    case _ =>
      scala.util.Try(segment.toDouble).toOption match {
        case Some(rate) => Right(StrikeValue(rate))
        case None       => Left(s"invalid strike dimension: $segment")
      }
  }
}
