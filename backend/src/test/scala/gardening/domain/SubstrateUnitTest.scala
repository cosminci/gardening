package gardening.domain

import cats.syntax.option.*
import io.github.iltotore.iron.*

class SubstrateUnitTest extends munit.FunSuite:

  test("should accept a mix of distinct components whose shares total at most 100"):
    val parts = List(
      SubstratePart(SubstrateComponent.Perlite, share = 30),
      SubstratePart(SubstrateComponent.PineBark, share = 70)
    )
    assert(Substrate.of(parts).isRight)

  test("should reject an empty mix"):
    assertEquals(Substrate.of(Nil).swap.toOption, SubstrateError.Empty.some)

  test("should reject a mix that repeats a component"):
    val parts = List(
      SubstratePart(SubstrateComponent.Perlite, share = 30),
      SubstratePart(SubstrateComponent.Perlite, share = 30)
    )
    assertEquals(Substrate.of(parts).swap.toOption, SubstrateError.DuplicateComponent.some)

  test("should reject a mix whose shares exceed 100"):
    val parts = List(
      SubstratePart(SubstrateComponent.Perlite, share = 60),
      SubstratePart(SubstrateComponent.PineBark, share = 60)
    )
    assertEquals(Substrate.of(parts).swap.toOption, SubstrateError.ExceedsTotal.some)
