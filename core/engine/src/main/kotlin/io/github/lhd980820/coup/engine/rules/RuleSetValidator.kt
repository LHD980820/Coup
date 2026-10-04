package io.github.lhd980820.coup.engine.rules

public data class ValidationIssue(public val code: String, public val message: String)

/** 룰셋이 엔진 불변식을 깨지 않는지 검사한다. 빈 목록이면 유효. */
public object RuleSetValidator {
    /** 교환이 한 번에 뽑는 최대 장수(여유분). 덱이 이보다 작으면 교환이 불가능해질 수 있다. */
    private const val MAX_EXCHANGE_DRAW = 2

    public fun validate(ruleSet: RuleSet): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val p = ruleSet.params

        duplicates(ruleSet.roles.map { it.id.value }).forEach {
            issues += ValidationIssue("DUPLICATE_ROLE", "duplicate role id: $it")
        }
        duplicates(ruleSet.actions.map { it.id.value }).forEach {
            issues += ValidationIssue("DUPLICATE_ACTION", "duplicate action id: $it")
        }

        val actionIds = ruleSet.actions.map { it.id }.toSet()
        for (role in ruleSet.roles) {
            (role.grantsActions + role.blocksActions).filterNot { it in actionIds }.forEach {
                issues += ValidationIssue("UNKNOWN_ACTION_REF", "role ${role.id.value} references unknown action ${it.value}")
            }
            if (role.copies <= 0) {
                issues += ValidationIssue("INVALID_COPIES", "role ${role.id.value} must have at least 1 copy")
            }
        }

        if (p.minPlayers < 2 || p.maxPlayers < p.minPlayers) {
            issues += ValidationIssue("INVALID_PLAYER_RANGE", "player range ${p.minPlayers}..${p.maxPlayers} is invalid")
        }
        if (p.handSize < 1) {
            issues += ValidationIssue("INVALID_HAND_SIZE", "handSize must be >= 1")
        }

        val needed = p.maxPlayers * p.handSize + MAX_EXCHANGE_DRAW
        if (ruleSet.totalCards < needed) {
            issues += ValidationIssue("DECK_TOO_SMALL", "deck has ${ruleSet.totalCards} cards but needs at least $needed")
        }

        val forced = ruleSet.actions.count { it.isForcedWhenRich }
        if (forced != 1) {
            issues += ValidationIssue("FORCED_ACTION_COUNT", "exactly one forced action is required but found $forced")
        }

        val alwaysAvailable = ruleSet.actions.any {
            it.cost == 0 && it.targeting is Targeting.None && ruleSet.rolesGranting(it.id).isEmpty()
        }
        if (!alwaysAvailable) {
            issues += ValidationIssue("NO_SAFE_ACTION", "at least one free, claim-less, untargeted action is required")
        }
        return issues
    }

    private fun duplicates(values: List<String>): Set<String> =
        values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
