package gardening.domain

import cats.syntax.option.*
import io.github.iltotore.iron.*

import java.util.UUID

class SubstrateUnitTest extends munit.FunSuite:

  private val perliteId  = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val pineBarkId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000004"))

  test("should accept a mix of distinct components whose shares total at most 100"):
    val parts = List(
      SubstratePart(perliteId, share = 30),
      SubstratePart(pineBarkId, share = 70)
    )
    assert(Substrate.of(parts).isRight)

  test("should reject an empty mix"):
    assertEquals(Substrate.of(Nil).swap.toOption, SubstrateError.Empty.some)

  test("should reject a mix that repeats a component"):
    val parts = List(
      SubstratePart(perliteId, share = 30),
      SubstratePart(perliteId, share = 30)
    )
    assertEquals(Substrate.of(parts).swap.toOption, SubstrateError.DuplicateComponent.some)

  test("should reject a mix whose shares exceed 100"):
    val parts = List(
      SubstratePart(perliteId, share = 60),
      SubstratePart(pineBarkId, share = 60)
    )
    assertEquals(Substrate.of(parts).swap.toOption, SubstrateError.ExceedsTotal.some)
