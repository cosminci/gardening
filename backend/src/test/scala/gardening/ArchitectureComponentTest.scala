package gardening

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import munit.FunSuite

class ArchitectureComponentTest extends FunSuite:

  test("should keep the domain independent from adapters and the application"):
    noClasses()
      .that()
      .resideInAPackage("gardening.domain..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("gardening.adapters..", "gardening.app..")
      .check(new ClassFileImporter().importPackages("gardening.domain"))
