import { render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import { PesticideArchiveConfirmation } from "../../src/app/PesticideArchiveConfirmation";
import * as Journal from "../../src/domain/Journal";

const neem: Journal.Pesticide = {
  id: Journal.pesticideId("00000000-0000-4000-8001-000000000003"),
  data: {
    name: Journal.pesticideName("Neem oil"),
    type: "insecticide",
    maybeInfo: null,
  },
  status: "active",
};

describe("pesticide archive confirmation", () => {
  it("should label the dialog and buttons for archiving the named pesticide", () => {
    render(() => (
      <PesticideArchiveConfirmation
        pesticide={neem}
        completed={false}
        onConfirm={() => Promise.resolve(undefined)}
        onCancel={() => undefined}
      />
    ));
    const dialog = screen.getByRole("alertdialog", { name: "Archive Neem oil" });
    expect(within(dialog).getByRole("button", { name: "Archive permanently" })).toBeInTheDocument();
    expect(
      within(dialog).getByText(/will no longer be offered for new operations/),
    ).toBeInTheDocument();
  });

  it("should restore focus to the pesticide's archive control on cancel, but not once completed", async () => {
    const opener = document.createElement("button");
    opener.id = `archive-pesticide-${neem.id}`;
    document.body.appendChild(opener);

    const cancelled = render(() => (
      <PesticideArchiveConfirmation
        pesticide={neem}
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
      <PesticideArchiveConfirmation
        pesticide={neem}
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
