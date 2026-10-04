package io.github.lhd980820.coup.engine.model

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
public value class PlayerId(public val value: String)

@JvmInline
@Serializable
public value class RoleId(public val value: String)

@JvmInline
@Serializable
public value class ActionId(public val value: String)

/** 물리 카드 1장마다 고유한 ID. 역할(RoleId)과 분리되어 있어 교환/교체 시 카드 추적이 정확하다. */
@JvmInline
@Serializable
public value class CardId(public val value: Int)
