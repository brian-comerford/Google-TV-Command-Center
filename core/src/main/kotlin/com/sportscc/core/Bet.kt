package com.sportscc.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A single (straight) bet: a moneyline, spread, game total or player prop on its own.
 * Deliberately no odds or stake: the app only says whether it hit.
 */
@Serializable
@SerialName("bet")
data class BetWatch(
    val id: String,
    val leg: Leg,
    /** When it was added; games that started well before this belong to an earlier slate. */
    val createdAt: Long = 0,
) : WatchItem {
    override val league: League get() = leg.league
    override val key: String get() = "bet:$id"
    override val displayName: String get() = ParlayEngine.label(leg)

    val kind: String
        get() = when (leg) {
            is MoneylineLeg -> "MONEYLINE"
            is SpreadLeg -> "SPREAD"
            is TotalLeg -> "TOTAL"
            is PropLeg -> "PROP"
        }
}

object BetEngine {
    fun evaluate(b: BetWatch, snap: Snapshot): LegState = ParlayEngine.evaluateLeg(b.leg, b.createdAt, snap)

    fun settlement(b: BetWatch, snap: Snapshot): Settlement? {
        val s = evaluate(b, snap)
        return if (b.leg.result == null && s.status.decided) Settlement(b.key, 0, s.status, s.value) else null
    }

    fun apply(b: BetWatch, settlements: List<Settlement>): BetWatch =
        settlements.firstOrNull { it.watchKey == b.key }?.let { b.copy(leg = ParlayEngine.settle(b.leg, it)) } ?: b

    /** Where the bet stands in words: "Covering by 3.5", "41 pts · needs 7 more", "Hit". */
    fun describe(s: LegState): String {
        when (s.status) {
            LegStatus.WON -> return "Hit"
            LegStatus.LOST -> return "Lost"
            LegStatus.PUSH -> return "Push"
            LegStatus.PENDING -> return if (s.game == null) "No game on today's slate" else "Not started"
            else -> Unit
        }
        val v = s.value ?: 0.0
        return when (val leg = s.leg) {
            is MoneylineLeg -> when {
                v > 0 -> "Leading by ${StatFormat.number(v)}"
                v < 0 -> "Trailing by ${StatFormat.number(-v)}"
                else -> "Tied"
            }
            is SpreadLeg -> {
                val cover = v + leg.points
                if (cover > 0) "Covering by ${StatFormat.number(cover)}"
                else "Not covering · needs ${StatFormat.number(floor(-cover) + 1)} more"
            }
            is TotalLeg -> {
                val pts = StatFormat.number(v)
                if (leg.side == Prop.Side.OVER) "$pts pts · needs ${StatFormat.number(floor(leg.line - v) + 1)} more"
                else "$pts pts · ${StatFormat.number(ceil(leg.line - v) - 1)} to spare"
            }
            is PropLeg -> {
                val line = leg.prop.line
                if (leg.prop.side == Prop.Side.OVER) "${StatFormat.number(v)} · needs ${StatFormat.number(floor(line - v) + 1)} more"
                else "${StatFormat.number(v)} · ${StatFormat.number(ceil(line - v) - 1)} to spare"
            }
        }
    }
}
