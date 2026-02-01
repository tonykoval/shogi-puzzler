package shogi.puzzler.domain

import shogi.format.forsyth.Sfen
import shogi.format.usi.Usi

/**
 * Represents a single node in the repertoire tree.
 */
case class RepertoireNode(
    sfen: Sfen,
    lastMove: Option[Usi],
    comment: Option[String] = None,
    children: Seq[RepertoireNode] = Seq.empty,
    isCorrect: Boolean = true // Whether this is part of the player's intended repertoire
)

/**
 * A named collection of variations for a specific opening or side.
 */
case class RepertoireTree(
    id: String,
    name: String,
    ownerEmail: Option[String],
    rootSfen: String = "lnsgkgsnl/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL b - 1",
    nodes: Map[String, RepertoireNodeData] // Map from SFEN to node data
)

/**
 * Flat representation of a node for database storage.
 */
case class RepertoireNodeData(
    sfen: String,
    moves: Seq[RepertoireMove] // Possible moves from this position
)

case class RepertoireMove(
    usi: String,
    nextSfen: String,
    isMain: Boolean = true,
    comment: Option[String] = None
)
