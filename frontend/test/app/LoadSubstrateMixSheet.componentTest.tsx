import * as Testing from "@solidjs/testing-library";
import * as Vitest from "vitest";
import { LoadSubstrateMixSheet } from "../../src/app/LoadSubstrateMixSheet";
import * as Journal from "../../src/domain/Journal";
import { deleteSubstrateMixControlId } from "../../src/app/OperationControlIds";

const perliteId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000003");
const pumiceId = Journal.substrateComponentId("00000000-0000-4000-8000-000000000005");
const perlite: Journal.SubstrateComponent = {
  id: perliteId,
  data: { name: Journal.substrateComponentName("Perlite"), maybeInfo: null },
  status: "active",
};

const availableMix: Journal.SubstrateMix = {
  id: Journal.substrateMixId("00000000-0000-4000-8000-000000000009"),
  name: Journal.substrateMixName("Standard mix"),
  maybeNotes: Journal.substrateMixNotes("Works well for aroids"),
  substrate: Journal.substrate([{ component: perliteId, share: Journal.percentage(100) }]),
};

const unavailableMix: Journal.SubstrateMix = {
  id: Journal.substrateMixId("00000000-0000-4000-8000-000000000010"),
  name: Journal.substrateMixName("Discontinued mix"),
  maybeNotes: null,
  substrate: Journal.substrate([{ component: pumiceId, share: Journal.percentage(100) }]),
};

Vitest.describe("LoadSubstrateMixSheet", () => {
  Vitest.it("should show an empty state and focus the panel when no mixes exist", () => {
    Testing.render(() => (
      <LoadSubstrateMixSheet
        mixes={[]}
        components={[perlite]}
        onLoad={() => undefined}
        onRequestDelete={() => undefined}
        onClose={() => undefined}
      />
    ));

    Vitest.expect(Testing.screen.getByRole("region", { name: "Load substrate mix" })).toHaveFocus();
    Vitest.expect(
      Testing.screen.getByText("No substrate mixes have been saved yet."),
    ).toBeInTheDocument();
  });

  Vitest.it("should load an available mix and show its components and notes", () => {
    const onLoad = Vitest.vi.fn();
    Testing.render(() => (
      <LoadSubstrateMixSheet
        mixes={[availableMix]}
        components={[perlite]}
        onLoad={onLoad}
        onRequestDelete={() => undefined}
        onClose={() => undefined}
      />
    ));

    Vitest.expect(Testing.screen.getByText("Works well for aroids")).toBeInTheDocument();
    Vitest.expect(Testing.screen.getByText("Perlite 100%")).toBeInTheDocument();
    const load = Testing.screen.getByRole("button", { name: "Load" });
    Vitest.expect(load).toBeEnabled();
    Testing.fireEvent.click(load);
    Vitest.expect(onLoad).toHaveBeenCalledWith(availableMix);
  });

  Vitest.it("should disable loading and warn when a mix references a removed component", () => {
    Testing.render(() => (
      <LoadSubstrateMixSheet
        mixes={[unavailableMix]}
        components={[perlite]}
        onLoad={() => undefined}
        onRequestDelete={() => undefined}
        onClose={() => undefined}
      />
    ));

    Vitest.expect(Testing.screen.getByRole("button", { name: "Load" })).toBeDisabled();
    Vitest.expect(Testing.screen.getByRole("alert")).toHaveTextContent(
      "One or more components in this mix are no longer available.",
    );
  });

  Vitest.it("should request deletion for the right mix and close", () => {
    const onRequestDelete = Vitest.vi.fn();
    const onClose = Vitest.vi.fn();
    Testing.render(() => (
      <LoadSubstrateMixSheet
        mixes={[availableMix]}
        components={[perlite]}
        onLoad={() => undefined}
        onRequestDelete={onRequestDelete}
        onClose={onClose}
      />
    ));

    Testing.fireEvent.click(
      Testing.screen.getByRole("button", { name: `Delete ${availableMix.name}` }),
    );
    Vitest.expect(onRequestDelete).toHaveBeenCalledWith(availableMix);
    Vitest.expect(
      document.getElementById(deleteSubstrateMixControlId(availableMix.id)),
    ).toBeInTheDocument();

    Testing.fireEvent.click(
      Testing.screen.getByRole("button", { name: "Collapse load mix editor" }),
    );
    Vitest.expect(onClose).toHaveBeenCalledOnce();
  });
});
