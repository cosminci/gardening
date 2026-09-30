import { render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { SubstrateComponentArchiveConfirmation } from "../../src/app/SubstrateComponentArchiveConfirmation";
import * as Journal from "../../src/domain/Journal";

const perlite: Journal.SubstrateComponent = {
  id: Journal.substrateComponentId("00000000-0000-4000-8000-000000000003"),
  data: {
    name: Journal.substrateComponentName("Perlite"),
    maybeInfo: null,
  },
  status: "active",
};

describe("substrate component archive confirmation", () => {
  it("should label the dialog and buttons for archiving the named substrate component", () => {
    render(() => (
      <SubstrateComponentArchiveConfirmation
        component={perlite}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    const dialog = screen.getByRole("alertdialog", { name: "Archive Perlite" });
    expect(within(dialog).getByRole("button", { name: "Archive permanently" })).toBeInTheDocument();
    expect(
      within(dialog).getByText(/will no longer be offered for new plants or repots/),
    ).toBeInTheDocument();
  });

  it("should restore focus to the component's archive control on cancel, but not once completed", async () => {
    const opener = document.createElement("button");
    opener.id = `archive-substrate-component-${perlite.id}`;
    document.body.appendChild(opener);

    const cancelled = render(() => (
      <SubstrateComponentArchiveConfirmation
        component={perlite}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    cancelled.unmount();
    await waitFor(() => {
      expect(opener).toHaveFocus();
    });

    opener.blur();
    const completed = render(() => (
      <SubstrateComponentArchiveConfirmation
        component={perlite}
        completed={true}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    completed.unmount();

    expect(opener).not.toHaveFocus();
    opener.remove();
  });
});
