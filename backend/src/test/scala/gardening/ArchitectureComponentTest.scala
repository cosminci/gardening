package gardening

import com.tngtech.archunit.core.importer.{ClassFileImporter, ImportOption}
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import munit.FunSuite

class ArchitectureComponentTest extends FunSuite:

  private val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("gardening")

  test("should keep domain free of any outgoing dependency"):
    noClasses()
      .that()
      .resideInAPackage("gardening.domain..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("gardening.capabilities..", "gardening.ports..", "gardening.usecases..", "gardening.adapters..", "gardening.app..")
      .check(classes)

  test("should keep capabilities free of any outgoing dependency"):
    noClasses()
      .that()
      .resideInAPackage("gardening.capabilities..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("gardening.domain..", "gardening.ports..", "gardening.usecases..", "gardening.adapters..", "gardening.app..")
      .check(classes)

  test("should keep ports dependent on domain only"):
    noClasses()
      .that()
      .resideInAPackage("gardening.ports..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("gardening.capabilities..", "gardening.usecases..", "gardening.adapters..", "gardening.app..")
      .check(classes)

  test("should keep usecases independent from adapters and the application"):
    noClasses()
      .that()
      .resideInAPackage("gardening.usecases..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("gardening.adapters..", "gardening.app..")
      .check(classes)

  test("should keep adapters free of cyclic dependencies on each other"):
    slices().matching("gardening.adapters.(*)..").should().beFreeOfCycles().check(classes)
