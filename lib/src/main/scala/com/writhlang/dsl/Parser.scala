package com.writhlang.dsl

import fastparse._
import fastparse.NoWhitespace._
import com.writhlang.risk.{KeyType, RiskFactorKey, ShiftType, Tenor}

object Parser {

  private sealed trait PropValue
  private case class NumberValue(value: Double) extends PropValue
  private case class RefValue(key: RiskFactorKey) extends PropValue
  private case class FlagValue(value: Boolean) extends PropValue
  private case class DateValue(text: String) extends PropValue
  private case class CurrencyValue(text: String) extends PropValue

  private case class InstrumentRaw(kind: String, id: String, props: List[(String, PropValue)])

  private sealed trait RawNode
  private case class RawPortfolio(name: String, children: List[RawNode]) extends RawNode
  private case class RawInstrument(instr: InstrumentRaw) extends RawNode

  def parseProgram(input: String): Either[String, Program] =
    fastparse.parse(input, program(_)) match {
      case Parsed.Success((shocks, rawNodes), _) =>
        val validated = rawNodes.map(validateNode)
        val errors = validated.collect { case Left(err) => err }
        if (errors.nonEmpty) Left(errors.mkString("; "))
        else Right(Program(shocks, validated.collect { case Right(v) => v }))
      case f: Parsed.Failure => Left(f.msg)
    }

  private def validateNode(raw: RawNode): Either[String, PortfolioNode] = raw match {
    case RawPortfolio(name, children) =>
      val validated = children.map(validateNode)
      val errors = validated.collect { case Left(err) => err }
      if (errors.nonEmpty) Left(errors.mkString("; "))
      else Right(Portfolio(name, validated.collect { case Right(v) => v }))
    case RawInstrument(instr) =>
      for {
        spec <- validateInstrument(instr)
        tradeDate <- tradeDateOf(instr)
        currency <- currencyOf(instr)
      } yield InstrumentLeaf(spec, tradeDate, currency)
  }

  /**
    * The trade's own start date, `tradeDate YYYY-MM-DD`. Optional in a single
    * snapshot (it defaults to the valuation date); a period run requires it, since
    * an implicit date would re-anchor the same trade at each snapshot and it would
    * never age.
    */
  private def tradeDateOf(raw: InstrumentRaw): Either[String, Option[java.time.LocalDate]] =
    raw.props.toMap.get("tradeDate") match {
      case None => Right(None)
      case Some(DateValue(text)) =>
        try Right(Some(java.time.LocalDate.parse(text)))
        catch {
          case _: java.time.format.DateTimeParseException =>
            Left(s"instrument ${raw.id} property tradeDate is not a valid date: $text (expected YYYY-MM-DD)")
        }
      case Some(_) =>
        Left(s"instrument ${raw.id} property tradeDate must be a date (YYYY-MM-DD)")
    }

  /**
    * The trade's currency, `currency EUR` — three uppercase letters. Absent, the
    * trade is taken to be denominated in the reporting currency (no translation).
    */
  private def currencyOf(raw: InstrumentRaw): Either[String, Option[Currency]] =
    raw.props.toMap.get("currency") match {
      case None                      => Right(None)
      case Some(CurrencyValue(code)) => Right(Some(Currency(code)))
      case Some(_) =>
        Left(s"instrument ${raw.id} property currency must be a three-letter uppercase code, e.g. EUR")
    }

  private def validateInstrument(raw: InstrumentRaw): Either[String, InstrumentSpec] = {
    val props = raw.props.toMap

    def reqNumber(name: String): Either[String, Double] =
      props.get(name) match {
        case Some(NumberValue(value)) => Right(value)
        case Some(_) => Left(s"instrument ${raw.id} property $name must be a number")
        case None => Left(s"instrument ${raw.id} missing property: $name")
      }

    def optNumber(name: String, default: Double): Either[String, Double] =
      props.get(name) match {
        case None => Right(default)
        case Some(NumberValue(value)) => Right(value)
        case Some(_) => Left(s"instrument ${raw.id} property $name must be a number")
      }

    def reqPositiveInt(name: String): Either[String, Int] =
      reqNumber(name).flatMap { v =>
        if (v % 1.0 == 0.0 && v > 0.0) Right(v.toInt)
        else Left(s"instrument ${raw.id} property $name must be a positive integer")
      }

    def reqPositiveNumber(name: String): Either[String, Double] =
      reqNumber(name).flatMap { v =>
        if (v > 0.0) Right(v) else Left(s"instrument ${raw.id} property $name must be > 0")
      }

    def optPositiveInt(name: String, default: Int): Either[String, Int] =
      props.get(name) match {
        case None => Right(default)
        case Some(NumberValue(v)) =>
          if (v % 1.0 == 0.0 && v > 0.0) Right(v.toInt)
          else Left(s"instrument ${raw.id} property $name must be a positive integer")
        case Some(_) => Left(s"instrument ${raw.id} property $name must be a number")
      }

    /**
      * A payment frequency has to generate whole-month steps, so it must divide
      * 12: 1, 2, 3, 4, 6 or 12 payments a year. Anything else cannot be turned
      * into a cashflow schedule and is rejected here rather than silently
      * approximated in pricing.
      */
    def optFrequency(name: String, default: Int): Either[String, Int] =
      optPositiveInt(name, default).flatMap { freq =>
        if (12 % freq == 0) Right(freq)
        else Left(s"instrument ${raw.id} property $name must divide 12 (1, 2, 3, 4, 6 or 12), got $freq")
      }

    def reqRef(name: String): Either[String, RiskFactorKey] =
      props.get(name) match {
        case Some(RefValue(key)) => Right(key)
        case Some(_) => Left(s"instrument ${raw.id} property $name must be a market-data reference")
        case None => Left(s"instrument ${raw.id} missing property: $name")
      }

    def optFlag(default: Boolean): Either[String, Boolean] =
      props.get("flag") match {
        case None => Right(default)
        case Some(FlagValue(v)) => Right(v)
        case Some(_) => Left(s"instrument ${raw.id} flag must be call or put")
      }

    raw.kind match {
      case "bond" =>
        for {
          notional <- reqNumber("notional")
          coupon <- reqNumber("coupon")
          maturity <- reqPositiveInt("maturity")
          discount <- reqRef("discountCurve")
          credit <- reqRef("creditCurve")
          freq <- optFrequency("freq", 1)
        } yield BondSpec(raw.id, notional, coupon, maturity, discount, credit, freq)

      case "mortgage" =>
        for {
          notional <- reqNumber("notional")
          noteRate <- reqNumber("noteRate")
          term <- reqPositiveInt("term")
          discount <- reqRef("discountCurve")
          credit <- reqRef("creditCurve")
          prepay <- reqRef("prepayCurve")
        } yield MortgageSpec(raw.id, notional, noteRate, term, discount, credit, prepay)

      case "mbs" =>
        for {
          notional <- reqNumber("notional")
          wac <- reqNumber("wac")
          wam <- reqPositiveInt("wam")
          discount <- reqRef("discountCurve")
          credit <- reqRef("creditCurve")
          prepay <- reqRef("prepayCurve")
        } yield MbsPoolSpec(raw.id, notional, wac, wam, discount, credit, prepay)

      case "swap" =>
        for {
          notional <- reqNumber("notional")
          fixedRate <- reqNumber("fixedRate")
          maturity <- reqPositiveInt("maturity")
          discount <- reqRef("discountCurve")
          credit <- reqRef("creditCurve")
          freq <- optFrequency("freq", 1)
        } yield SwapSpec(raw.id, notional, fixedRate, maturity, discount, credit, freq)

      case "cap" =>
        for {
          notional <- reqNumber("notional")
          strike <- reqNumber("strike")
          maturity <- reqPositiveInt("maturity")
          freq <- optFrequency("freq", 4)
          discount <- reqRef("discountCurve")
          credit <- reqRef("creditCurve")
          vol <- reqRef("volSurface")
        } yield CapSpec(raw.id, notional, strike, maturity, freq, discount, credit, vol)

      case "swaption" =>
        for {
          notional <- reqNumber("notional")
          strike <- reqNumber("strike")
          expiry <- reqPositiveNumber("expiry")
          maturity <- reqPositiveInt("maturity")
          freq <- optFrequency("freq", 2)
          isPayer <- optFlag(default = true)
          discount <- reqRef("discountCurve")
          credit <- reqRef("creditCurve")
          vol <- reqRef("volSurface")
        } yield SwaptionSpec(raw.id, notional, strike, expiry, maturity, freq, isPayer, discount, credit, vol)

      case "fxforward" =>
        for {
          notional <- reqNumber("notional")
          fxRate <- reqNumber("fxRate")
          maturity <- reqPositiveNumber("maturity")
          domestic <- reqRef("domesticCurve")
          foreign <- reqRef("foreignCurve")
          spot <- reqRef("fxSpot")
        } yield FxForwardSpec(raw.id, notional, fxRate, maturity, domestic, foreign, spot)

      case other => Left(s"unknown instrument type: $other")
    }
  }

  private def refKey(field: String, name: String): RiskFactorKey = field match {
    case "discountCurve" | "domesticCurve" | "foreignCurve" => RiskFactorKey(KeyType.DiscountCurve, name)
    case "creditCurve" => RiskFactorKey(KeyType.CreditCurve, name)
    case "prepayCurve" => RiskFactorKey(KeyType.Prepay, name)
    case "volSurface"  => RiskFactorKey(KeyType.SwaptionVolatility, name)
    case "fxSpot"      => RiskFactorKey(KeyType.FxSpot, name)
  }

  // ---- grammar ----

  private def program[_: P]: P[(List[Shock], List[RawNode])] = P(
    ws ~ shocksBlock.? ~ ws ~ (ws ~ node).rep ~ ws ~ End
  ).map { case (shocksOpt, nodes) => (shocksOpt.getOrElse(Nil), nodes.toList) }

  private def node[_: P]: P[RawNode] = P(portfolioBlock | instrumentBlock)

  private def portfolioBlock[_: P]: P[RawNode] = P(
    "portfolio" ~ ws ~ ident ~ ws ~ "{" ~ ws ~ (ws ~ node).rep ~ ws ~ "}"
  ).map { case (name, children) => RawPortfolio(name, children.toList) }

  private def instrumentBlock[_: P]: P[RawNode] = P(
    "instrument" ~ ws ~ kind ~ ws ~ ident ~ ws ~ "{" ~ ws ~ prop.rep ~ ws ~ "}"
  ).map { case (kind, id, props) => RawInstrument(InstrumentRaw(kind, id, props.toList)) }

  private def kind[_: P]: P[String] = P(("bond" | "mortgage" | "mbs" | "swaption" | "swap" | "cap" | "fxforward").!)

  private def shocksBlock[_: P]: P[List[Shock]] = P(
    "shocks" ~ ws ~ "{" ~ ws ~ (ws ~ shockEntry).rep ~ ws ~ "}"
  ).map(_.toList)

  private def shockEntry[_: P]: P[Shock] = P(
    "shock" ~ ws ~ ident ~ ws ~ "{" ~ ws ~ shockMove.rep ~ ws ~ "}"
  ).map { case (name, moves) => Shock(name, moves.toList) }

  private def shockMove[_: P]: P[ShockMove] = P((curveMove | scalarMove) ~ entryEnd)

  private def curveMove[_: P]: P[ShockMove] = P(curveKw ~ ws ~ ident ~ ws ~ curveTail).map {
    case (keyType, name, build) => build(keyType, name)
  }

  private def curveTail[_: P]: P[(KeyType, String) => ShockMove] = P(curveShapeTail | curvePointTail)

  private def curveShapeTail[_: P]: P[(KeyType, String) => ShockMove] = P(shape).map { s =>
    (kt: KeyType, name: String) => ShapeMove(RiskFactorKey(kt, name), s)
  }

  private def curvePointTail[_: P]: P[(KeyType, String) => ShockMove] = P(tenor ~ ws ~ shiftType.? ~ ws ~ number).map {
    case (t, stOpt, amount) =>
      (kt: KeyType, name: String) =>
        PointMove(RiskFactorKey(kt, name, tenor = Some(t)), stOpt.getOrElse(ShiftType.defaultFor(kt)), amount)
  }

  private def scalarMove[_: P]: P[ShockMove] = P(scalarKw ~ ws ~ ident ~ ws ~ shiftType.? ~ ws ~ number).map {
    case (keyType, name, stOpt, amount) =>
      PointMove(RiskFactorKey(keyType, name), stOpt.getOrElse(ShiftType.defaultFor(keyType)), amount)
  }

  private def shape[_: P]: P[CurveShift] = P(
    ("parallel" ~ ws ~ number).map(v => FlatShift(v): CurveShift) |
      ("bucket" ~ ws ~ number ~ ws ~ number).map { case (t, a) => BucketShift(t, a): CurveShift } |
      ("twist" ~ ws ~ number ~ ws ~ number ~ (ws ~ number).?).map {
        case (short, long, pivot) => TwistShift(short, long, pivot.getOrElse(10.0)): CurveShift
      } |
      ("sine" ~ ws ~ number ~ ws ~ number ~ (ws ~ number).?).map {
        case (amp, omega, phase) => SineShift(amp, omega, phase.getOrElse(0.0)): CurveShift
      }
  )

  private def tenor[_: P]: P[Tenor] = P(number ~ CharIn("YM").!).map { case (v, unit) =>
    Tenor(if (unit == "M") v / 12.0 else v)
  }

  private def shiftType[_: P]: P[ShiftType] = P(("absolute" | "relative").!).map(s => ShiftType.fromName(s).get)

  private def curveKw[_: P]: P[KeyType] = P(("discountcurve" | "creditcurve" | "indexcurve" | "yieldcurve").!).map(curveKeyTypes)
  private def scalarKw[_: P]: P[KeyType] = P(("fxspot" | "prepay" | "swaptionvol" | "capfloorvol").!).map(scalarKeyTypes)

  private val curveKeyTypes: Map[String, KeyType] = Map(
    "discountcurve" -> KeyType.DiscountCurve,
    "creditcurve" -> KeyType.CreditCurve,
    "indexcurve" -> KeyType.IndexCurve,
    "yieldcurve" -> KeyType.YieldCurve
  )
  private val scalarKeyTypes: Map[String, KeyType] = Map(
    "fxspot" -> KeyType.FxSpot,
    "prepay" -> KeyType.Prepay,
    "swaptionvol" -> KeyType.SwaptionVolatility,
    "capfloorvol" -> KeyType.CapFloorVolatility
  )

  // ---- props ----

  private def prop[_: P]: P[(String, PropValue)] = P(refProp | flagProp | dateProp | currencyProp | numberProp)

  private def dateProp[_: P]: P[(String, PropValue)] =
    P("tradeDate" ~ ws ~ CharsWhile(ch => ch.isDigit || ch == '-', 1).! ~ entryEnd)
      .map(text => ("tradeDate", DateValue(text): PropValue))

  private def currencyProp[_: P]: P[(String, PropValue)] =
    P("currency" ~ ws ~ CharIn("ABCDEFGHIJKLMNOPQRSTUVWXYZ").rep(exactly = 3).! ~ entryEnd)
      .map(text => ("currency", CurrencyValue(text): PropValue))

  private def refProp[_: P]: P[(String, PropValue)] = P(
    ("discountCurve" | "creditCurve" | "prepayCurve" | "volSurface" | "domesticCurve" | "foreignCurve" | "fxSpot").! ~ ws ~ ident ~ entryEnd
  ).map { case (field, name) => (field, RefValue(refKey(field, name))) }

  private def flagProp[_: P]: P[(String, PropValue)] = P(
    ("call" | "put").! ~ entryEnd
  ).map { s => ("flag", FlagValue(s == "call")) }

  private def numberProp[_: P]: P[(String, PropValue)] = P(
    ident ~ ws ~ number ~ entryEnd
  ).map { case (name, value) => (name, NumberValue(value)) }

  // ---- tokens ----

  private def ident[_: P]: P[String] = P(CharIn("a-zA-Z") ~ CharsWhileIn("a-zA-Z0-9_.", 0)).!

  private def number[_: P]: P[Double] = P(
    (("-" | "+").? ~ CharsWhileIn("0-9", 1) ~ ("." ~ CharsWhileIn("0-9", 1)).?).!
  ).map(_.toDouble)

  private def ws[_: P]: P[Unit] = P(CharsWhileIn(" \r\n\t").rep)

  private def entryEnd[_: P]: P[Unit] = P(ws ~ ";".? ~ ws)
}

