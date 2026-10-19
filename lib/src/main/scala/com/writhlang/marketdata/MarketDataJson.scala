package com.writhlang.marketdata

import com.writhlang.marketdata.bootstrap._
import com.writhlang.risk._
import com.writhlang.time.TimeConventions

import java.time.LocalDate

/**
  * Loads a [[MarketData]] snapshot from JSON. The schema declares named market
  * objects, each resolving to a [[RiskFactorKey]]:
  *
  * - `curves`: name -> `{ "type": "DiscountCurve", "instruments": [...] }` (a
  *   bootstrapped curve) or `{ "type": "CreditCurve", "flat": 0.0015 }` (a flat
  *   curve, e.g. a credit spread).
  * - `fxSpots`: name -> number.
  * - `volSurfaces`: name -> `{ "flat": 0.20 }`.
  * - `prepayVectors`: name -> `{ "termMonths": 360, "flat": 0.02 }` or
  *   `{ "termMonths": 360, "ramp": { "start": 0.02, "end": 0.06, "months": 24 } }`.
  */
object MarketDataJson {

  def parse(json: String, conventions: TimeConventions = TimeConventions.default): Either[String, MarketData] = {
    try {
      val data = ujson.read(json)
      val obj = data.obj

      var market = MarketData(conventions = conventions)
      val errors = scala.collection.mutable.ListBuffer.empty[String]

      // A dated snapshot: the valuation date. Omitted -> the deterministic default.
      obj.get("asOf").foreach { asOfValue =>
        parseAsOf(asOfValue.str) match {
          case Right(date) => market = market.withAsOf(date)
          case Left(err)   => errors += err
        }
      }

      obj.get("curves").foreach { curves =>
        curves.obj.foreach { case (name, value) =>
          parseCurve(name, value, market.asOf, conventions) match {
            case Right((key, curve, quotesOpt)) =>
              market = quotesOpt match {
                case Some(quotes) => market.withCurveAndQuotes(key, curve, quotes)
                case None         => market.withCurve(key, curve)
              }
            case Left(err) => errors += err
          }
        }
      }

      obj.get("fxSpots").foreach { fx =>
        fx.obj.foreach { case (name, value) =>
          market = market.withFxSpot(RiskFactorKey(KeyType.FxSpot, name), SpotQuote(value.num))
        }
      }

      obj.get("volSurfaces").foreach { vols =>
        vols.obj.foreach { case (name, value) =>
          market = market.withVolSurface(RiskFactorKey(KeyType.SwaptionVolatility, name), VolSurface.flat(value("flat").num))
        }
      }

      obj.get("prepayVectors").foreach { prepays =>
        prepays.obj.foreach { case (name, value) =>
          market = market.withPrepayVector(RiskFactorKey(KeyType.Prepay, name), parsePrepay(value))
        }
      }

      if (errors.nonEmpty) Left(errors.mkString("; ")) else Right(market)
    } catch {
      case e: Exception => Left(s"invalid market-data JSON: ${e.getMessage}")
    }
  }

  private def parseAsOf(value: String): Either[String, LocalDate] =
    try Right(LocalDate.parse(value))
    catch { case e: Exception => Left(s"invalid asOf date '$value': ${e.getMessage}") }

  private def parseCurve(
    name: String,
    value: ujson.Value,
    asOf: LocalDate,
    conventions: TimeConventions
  ): Either[String, (RiskFactorKey, Curve, Option[QuoteSet])] = {
    val typeName = value("type").str
    KeyType.fromName(typeName).toRight(s"curve $name: unknown type $typeName").flatMap { keyType =>
      val key = RiskFactorKey(keyType, name)
      if (value.obj.contains("flat")) {
        Right((key, Curve.flat(value("flat").num), None))
      } else {
        for {
          instruments <- parseInstruments(value("instruments"))
          curve <- Bootstrapper.bootstrap(QuoteSet(instruments), asOf, conventions)
        } yield (key, curve, Some(QuoteSet(instruments)))
      }
    }
  }

  private def parseInstruments(arr: ujson.Value): Either[String, List[ParInstrument]] =
    arr.arr.toList.foldLeft[Either[String, List[ParInstrument]]](Right(Nil)) {
      case (acc, item) =>
        for {
          list <- acc
          instr <- parseInstrument(item)
        } yield list :+ instr
    }

  private def parseInstrument(item: ujson.Value): Either[String, ParInstrument] =
    item("kind").str match {
      case "deposit" =>
        Tenor.parse(item("tenor").str).map(t => Deposit(t, item("rate").num))
      case "future" =>
        for {
          start <- Tenor.parse(item("start").str)
          end <- Tenor.parse(item("end").str)
        } yield Future(start, end, item("rate").num)
      case "swap" =>
        for (t <- Tenor.parse(item("tenor").str))
          yield Swap(t, item("rate").num, item.obj.get("freq").map(_.num.toInt).getOrElse(1))
      case other => Left(s"unknown par instrument kind: $other")
    }

  private def parsePrepay(value: ujson.Value): PrepayVector = {
    val termMonths = value("termMonths").num.toInt
    if (value.obj.contains("flat")) {
      PrepayVector.constant(value("flat").num, termMonths)
    } else {
      val ramp = value("ramp").obj
      PrepayVector.fromRamp(ramp("start").num, ramp("end").num, ramp("months").num.toInt, termMonths)
    }
  }
}
