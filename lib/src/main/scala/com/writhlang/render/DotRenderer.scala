package com.writhlang.render

import com.writhlang.engine._

object DotRenderer {
  def toDot(dag: Dag): String = {
    val edges = dag.nodes.values.toList.flatMap { instr =>
      instr.deps.map { dep => s"  \"$dep\" -> \"${instr.id}\";" }
    }

    val clusters = dag.nodes.values.toList.groupBy(instrumentKey).toList.flatMap {
      case (None, instrs) => instrs.map(renderNode)
      case (Some(instId), instrs) =>
        val body = instrs.map(renderNode).mkString("\n")
        List(
          s"  subgraph cluster_${sanitize(instId)} {",
          s"    label=\"$instId\";",
          "    color=\"#9aa0a6\";",
          body,
          "  }"
        )
    }

    (
      List(
        "digraph WrithDag {",
        "  rankdir=LR;",
        "  node [shape=box style=filled fontname=\"Helvetica\"];"
      ) ++ clusters ++ edges :+ "}"
    ).mkString("\n")
  }

  private def instrumentKey(instr: Instruction): Option[String] = {
    // Instrument-scoped node ids are "<family>:<kind>:<instId>:...". The factor
    // key portion now contains its own ':' separators (e.g. DiscountCurve:EUR),
    // so extract the instrument id from its fixed position instead of pattern
    // matching the whole tail.
    instr.id.split(":").toList match {
      case "price" :: _ :: instId :: _ => Some(instId)
      case "greek" :: _ :: instId :: _ => Some(instId)
      case _                           => None
    }
  }

  private def renderNode(instr: Instruction): String = {
    val (label, color) = nodeLabel(instr)
    s"  \"${instr.id}\" [label=\"$label\" fillcolor=\"$color\"];"
  }

  private def nodeLabel(instr: Instruction): (String, String) = instr.op match {
    case Const(_) => (s"${instr.id}\\nshock", "#fbbc04")
    case Sum => (s"${instr.id}\\nΣ", "#cfd8dc")
    case Accrued(_, _, _) => (s"${instr.id}\\naccrued", "#dcedc8")
    case CashFlow(_, _, _, _, _) => (s"${instr.id}\\ncash", "#c5e1a5")
    case Combine(_) => (s"${instr.id}\\npnl", "#f8bbd0")
    case Price(_, _, _) => (s"${instr.id}\\nprice", "#a7c7e7")
    case Theta(_, _) => (s"${instr.id}\\ntheta", "#fff9c4")
    case Carry(_, _, _, _) => (s"${instr.id}\\ncarry", "#ffe0b2")
    case DeltaCentral(_, _, _, _) => (s"${instr.id}\\ndelta", "#c8e6c9")
    case DeltaForward(_, _, _) => (s"${instr.id}\\ndelta", "#c8e6c9")
    case DeltaBackward(_, _, _) => (s"${instr.id}\\ndelta", "#c8e6c9")
    case Gamma(_, _, _, _) => (s"${instr.id}\\ngamma", "#b2dfdb")
    case CrossGamma(_, _, _, _, _, _) => (s"${instr.id}\\ncross", "#b39ddb")
    case LinearScenario(_, _) => (s"${instr.id}\\nlinear", "#e0e0e0")
    case QuadraticScenario(_, _, _, _) => (s"${instr.id}\\nquadratic", "#ffe0b2")
  }

  private def sanitize(value: String): String = value.replaceAll("[^a-zA-Z0-9_]", "_")
}
