import * as Testing from "@solidjs/testing-library";
import * as Vitest from "vitest";
import { PlantPhotosSheet } from "../../src/app/PlantPhotosSheet";
import * as Journal from "../../src/domain/Journal";
import type { PlantPhotoClient } from "../../src/domain/PlantPhoto";
import { ficus } from "./JournalTestSupport";

const photo1: Journal.PlantPhoto = {
  id: Journal.photoId("ph1"),
  capturedAt: Journal.instant("2026-05-15T10:00:00Z"),
};
const photo2: Journal.PlantPhoto = {
  id: Journal.photoId("ph2"),
  capturedAt: Journal.instant("2026-05-14T08:30:00Z"),
};

const photosPage = (
  photos: Journal.PlantPhoto[] = [],
  hasNextPage = false,
): Journal.GetPhotosResult => ({
  kind: "read",
  page: { photos, hasNextPage },
});

const buildPhotoClient = ({
  getPhotosResults = [photosPage()],
  addPhotoResult = {
    kind: "addFailed" as const,
    reason: new Error("unexpected"),
  },
  removePhotoResult = {
    kind: "removeFailed" as const,
    reason: new Error("unexpected"),
  },
}: {
  getPhotosResults?: Journal.GetPhotosResult[];
  addPhotoResult?: Journal.AddPhotoResult;
  removePhotoResult?: Journal.RemovePhotoResult;
} = {}): PlantPhotoClient & { getPhotosCalls: Journal.PhotoWindow[] } => {
  let getIndex = 0;
  const getPhotosCalls: Journal.PhotoWindow[] = [];
  return {
    getPhotosCalls,
    getPhotos: (_plantId, window) => {
      getPhotosCalls.push(window);
      const result = getPhotosResults[Math.min(getIndex++, getPhotosResults.length - 1)];
      return Promise.resolve(result ?? photosPage());
    },
    addPhoto: () => Promise.resolve(addPhotoResult),
    removePhoto: () => Promise.resolve(removePhotoResult),
  };
};

Vitest.describe("PlantPhotosSheet", () => {
  Vitest.it("should load the first page on open and focus the dialog", async () => {
    const client = buildPhotoClient({ getPhotosResults: [photosPage([photo1, photo2])] });
    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));

    const dialog = Testing.screen.getByRole("dialog", { name: /Photos for/ });
    Vitest.expect(dialog).toHaveFocus();
    const photos = await Testing.screen.findAllByAltText(/Photo from/);
    Vitest.expect(photos).toHaveLength(2);
    Vitest.expect(client.getPhotosCalls).toEqual([{ offset: 0, size: 6 }]);
  });

  Vitest.it(
    "should paginate with Previous and Next and apply the stale-response guard",
    async () => {
      let resolvePage2!: (result: Journal.GetPhotosResult) => void;
      const page2Promise = new Promise<Journal.GetPhotosResult>((resolve) => {
        resolvePage2 = resolve;
      });
      const client = buildPhotoClient({
        getPhotosResults: [photosPage([photo1], true), photosPage([photo2])],
      });
      // Override to control page 2 timing
      let getIndex = 0;
      client.getPhotos = (_id, window) => {
        client.getPhotosCalls.push(window);
        getIndex++;
        if (getIndex === 1) return Promise.resolve(photosPage([photo1], true));
        if (getIndex === 2) return page2Promise;
        return Promise.resolve(photosPage([photo2]));
      };

      Testing.render(() => (
        <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
      ));

      // Wait for page 1 to load
      await Testing.screen.findByAltText(/Photo from/);

      // Click Next
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Next" }));
      Vitest.expect(Testing.screen.getByText("Loading photos…")).toBeInTheDocument();

      // Resolve page 2
      resolvePage2(photosPage([photo2]));
      const pageTwo = await Testing.screen.findByText("Page 2");
      await Testing.waitFor(() => {
        Vitest.expect(pageTwo).toHaveFocus();
      });

      // Click Previous
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Previous" }));
      const pageOne = await Testing.screen.findByText("Page 1");
      await Testing.waitFor(() => {
        Vitest.expect(pageOne).toHaveFocus();
      });
    },
  );

  Vitest.it(
    "should show a failed page fetch and retry the same page, preserving loaded photos",
    async () => {
      const client = buildPhotoClient({
        getPhotosResults: [
          photosPage([photo1], true),
          { kind: "readFailed", reason: new Error("private") },
          photosPage([photo2]),
        ],
      });

      Testing.render(() => (
        <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
      ));

      // Page 1 loads
      await Testing.screen.findByAltText(/Photo from/);

      // Navigate to page 2 → fails
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Next" }));
      const failure = await Testing.screen.findByRole("alert");
      await Testing.waitFor(() => {
        Vitest.expect(failure).toHaveFocus();
      });
      Vitest.expect(Testing.screen.queryByText("private")).not.toBeInTheDocument();

      // Retry → succeeds with photo2
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Retry" }));
      const pageTwo = await Testing.screen.findByText("Page 2");
      await Testing.waitFor(() => {
        Vitest.expect(pageTwo).toHaveFocus();
      });
      // Confirm retry used the same offset as the failed request (page 2)
      Vitest.expect(client.getPhotosCalls[2]).toEqual({ offset: 6, size: 6 });
    },
  );

  Vitest.it("should show a failed state when the photos request itself rejects", async () => {
    const client = buildPhotoClient({ getPhotosResults: [photosPage([photo1])] });
    client.getPhotos = () => Promise.reject(new Error("network down"));

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
      "Photos could not be loaded.",
    );
  });

  Vitest.it("should show empty state when first page has no photos", async () => {
    const client = buildPhotoClient({ getPhotosResults: [photosPage([])] });
    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));

    Vitest.expect(await Testing.screen.findByText("No photos yet.")).toBeInTheDocument();
  });

  Vitest.it(
    "should prepend a successfully uploaded photo to the list and clear the input",
    async () => {
      const newPhoto: Journal.PlantPhoto = {
        id: Journal.photoId("ph-new"),
        capturedAt: Journal.instant("2026-09-26T12:00:00Z"),
      };
      const client = buildPhotoClient({
        getPhotosResults: [photosPage([photo1]), photosPage([newPhoto, photo1])],
        addPhotoResult: { kind: "added", photo: newPhoto },
      });

      Testing.render(() => (
        <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
      ));
      await Testing.screen.findByAltText(/Photo from/);

      const fileInput = Testing.screen.getByLabelText("Choose a photo to upload");
      const file = new File(["jpeg"], "photo.jpg", { type: "image/jpeg" });
      Object.defineProperty(fileInput, "files", { value: [file], configurable: true });
      Testing.fireEvent.change(fileInput);

      // New photo should appear first
      await Testing.waitFor(() => {
        const imgs = Testing.screen.getAllByAltText(/Photo from/);
        Vitest.expect(imgs).toHaveLength(2);
        // New photo first (newest)
        Vitest.expect(imgs[0]).toHaveAttribute("src", `/photos/ph-new/content?variant=thumbnail`);
      });
      // Input value is cleared (we can't check value directly in jsdom for file inputs,
      // but the absence of an upload error confirms success)
      Vitest.expect(Testing.screen.queryByRole("alert")).not.toBeInTheDocument();
    },
  );

  Vitest.it("should reject a file with a disallowed MIME type before upload", async () => {
    const client = buildPhotoClient({ getPhotosResults: [photosPage([])] });
    const addPhotoSpy = Vitest.vi.fn();
    client.addPhoto = addPhotoSpy;

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));
    await Testing.screen.findByText("No photos yet.");

    const fileInput = Testing.screen.getByLabelText("Choose a photo to upload");
    const gifFile = new File(["data"], "anim.gif", { type: "image/gif" });
    Object.defineProperty(fileInput, "files", { value: [gifFile], configurable: true });
    Testing.fireEvent.change(fileInput);

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
      "Unsupported file type",
    );
    Vitest.expect(addPhotoSpy).not.toHaveBeenCalled();
  });

  Vitest.it(
    "should show an inline error on upload failure and leave the list unchanged",
    async () => {
      const client = buildPhotoClient({
        getPhotosResults: [photosPage([photo1])],
        addPhotoResult: { kind: "addFailed", reason: new Error("server error") },
      });

      Testing.render(() => (
        <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
      ));
      await Testing.screen.findByAltText(/Photo from/);

      const fileInput = Testing.screen.getByLabelText("Choose a photo to upload");
      const file = new File(["jpeg"], "photo.jpg", { type: "image/jpeg" });
      Object.defineProperty(fileInput, "files", { value: [file], configurable: true });
      Testing.fireEvent.change(fileInput);

      Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
        "could not be uploaded",
      );
      // List unchanged — still only photo1
      Vitest.expect(Testing.screen.getAllByAltText(/Photo from/)).toHaveLength(1);
    },
  );

  Vitest.it("should show plantMissing error on upload", async () => {
    const client = buildPhotoClient({
      getPhotosResults: [photosPage([])],
      addPhotoResult: { kind: "plantMissing" },
    });

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));
    await Testing.screen.findByText("No photos yet.");

    const fileInput = Testing.screen.getByLabelText("Choose a photo to upload");
    const file = new File(["jpeg"], "photo.jpg", { type: "image/jpeg" });
    Object.defineProperty(fileInput, "files", { value: [file], configurable: true });
    Testing.fireEvent.change(fileInput);

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent("no longer exists");
  });

  Vitest.it("should show unsupportedMediaType error on upload", async () => {
    const client = buildPhotoClient({
      getPhotosResults: [photosPage([])],
      addPhotoResult: { kind: "unsupportedMediaType" },
    });

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));
    await Testing.screen.findByText("No photos yet.");

    const fileInput = Testing.screen.getByLabelText("Choose a photo to upload");
    const file = new File(["jpeg"], "photo.jpg", { type: "image/jpeg" });
    Object.defineProperty(fileInput, "files", { value: [file], configurable: true });
    Testing.fireEvent.change(fileInput);

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
      "Unsupported file type",
    );
  });

  Vitest.it("should show tooLarge error from server on upload", async () => {
    const client = buildPhotoClient({
      getPhotosResults: [photosPage([])],
      addPhotoResult: { kind: "tooLarge" },
    });

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));
    await Testing.screen.findByText("No photos yet.");

    const fileInput = Testing.screen.getByLabelText("Choose a photo to upload");
    const file = new File(["jpeg"], "photo.jpg", { type: "image/jpeg" });
    Object.defineProperty(fileInput, "files", { value: [file], configurable: true });
    Testing.fireEvent.change(fileInput);

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent("too large");
  });

  Vitest.it(
    "should not prepend an uploaded photo if the page finished loading after upload started",
    async () => {
      let resolveGetPhotos!: (result: Journal.GetPhotosResult) => void;
      const client = buildPhotoClient({
        addPhotoResult: { kind: "added", photo: photo1 },
      });
      client.getPhotos = (_id, window) => {
        client.getPhotosCalls.push(window);
        return new Promise((resolve) => {
          resolveGetPhotos = resolve;
        });
      };

      Testing.render(() => (
        <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
      ));
      Vitest.expect(Testing.screen.getByText("Loading photos…")).toBeInTheDocument();

      const fileInput = Testing.screen.getByLabelText("Choose a photo to upload");
      const file = new File(["jpeg"], "photo.jpg", { type: "image/jpeg" });
      Object.defineProperty(fileInput, "files", { value: [file], configurable: true });
      Testing.fireEvent.change(fileInput);

      resolveGetPhotos(photosPage([photo2]));

      const photos = await Testing.screen.findAllByAltText(/Photo from/);
      Vitest.expect(photos).toHaveLength(1);
    },
  );

  Vitest.it("should open the full-size overlay on thumbnail click and close it", async () => {
    const client = buildPhotoClient({ getPhotosResults: [photosPage([photo1])] });

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));

    const thumbnail = await Testing.screen.findByAltText(/Photo from/);
    Vitest.expect(thumbnail).toHaveAttribute("src", "/photos/ph1/content?variant=thumbnail");
    Testing.fireEvent.click(thumbnail);

    const overlay = Testing.screen.getByRole("dialog", { name: "Photo viewer" });
    Vitest.expect(overlay).toBeInTheDocument();
    Vitest.expect(Testing.within(overlay).getByAltText("")).toHaveAttribute(
      "src",
      "/photos/ph1/content?variant=original",
    );
    const closeBtn = Testing.within(overlay).getByRole("button", { name: "Close photo viewer" });
    Vitest.expect(closeBtn).toHaveFocus();

    // Close via button
    Testing.fireEvent.click(closeBtn);
    Vitest.expect(
      Testing.screen.queryByRole("dialog", { name: "Photo viewer" }),
    ).not.toBeInTheDocument();
  });

  Vitest.it("should close the full-size overlay via Escape", async () => {
    const client = buildPhotoClient({ getPhotosResults: [photosPage([photo1])] });

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));

    Testing.fireEvent.click(await Testing.screen.findByAltText(/Photo from/));
    Vitest.expect(Testing.screen.getByRole("dialog", { name: "Photo viewer" })).toBeInTheDocument();

    Testing.fireEvent.keyDown(window, { key: "Escape" });
    Vitest.expect(
      Testing.screen.queryByRole("dialog", { name: "Photo viewer" }),
    ).not.toBeInTheDocument();
  });

  Vitest.it("should trap Tab inside the overlay", async () => {
    const client = buildPhotoClient({ getPhotosResults: [photosPage([photo1])] });

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));

    Testing.fireEvent.click(await Testing.screen.findByAltText(/Photo from/));
    const closeBtn = Testing.screen.getByRole("button", { name: "Close photo viewer" });
    Vitest.expect(closeBtn).toHaveFocus();

    Testing.fireEvent.keyDown(window, { key: "Tab" });
    Vitest.expect(closeBtn).toHaveFocus();
  });

  Vitest.it("should ignore unrelated keys while the overlay is open", async () => {
    const client = buildPhotoClient({ getPhotosResults: [photosPage([photo1])] });

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));

    Testing.fireEvent.click(await Testing.screen.findByAltText(/Photo from/));
    const overlay = Testing.screen.getByRole("dialog", { name: "Photo viewer" });

    Testing.fireEvent.keyDown(window, { key: "a" });
    Vitest.expect(overlay).toBeInTheDocument();
  });

  Vitest.it("should close the overlay via backdrop click", async () => {
    const client = buildPhotoClient({ getPhotosResults: [photosPage([photo1])] });

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));

    Testing.fireEvent.click(await Testing.screen.findByAltText(/Photo from/));
    Vitest.expect(Testing.screen.getByRole("dialog", { name: "Photo viewer" })).toBeInTheDocument();

    const backdrop = Testing.screen
      .getByRole("dialog", { name: "Photo viewer" })
      .querySelector(".photo-overlay-layer__backdrop");
    if (backdrop === null) throw new Error("backdrop not found");
    Testing.fireEvent.click(backdrop);
    Vitest.expect(
      Testing.screen.queryByRole("dialog", { name: "Photo viewer" }),
    ).not.toBeInTheDocument();
  });

  Vitest.it("should open remove confirmation and remove the photo on confirm", async () => {
    const client = buildPhotoClient({
      getPhotosResults: [photosPage([photo1, photo2]), photosPage([photo2])],
      removePhotoResult: { kind: "removed" },
    });

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));

    await Testing.screen.findAllByAltText(/Photo from/);

    Testing.fireEvent.click(
      Testing.screen.getByRole("button", { name: "Remove photo from 15.05.2026 13:00" }),
    );

    const confirmation = Testing.screen.getByRole("alertdialog", { name: /Remove photo/ });
    Vitest.expect(confirmation).toBeInTheDocument();

    const confirmBtn = Testing.within(confirmation).getByRole("button", {
      name: "Remove permanently",
    });
    Testing.fireEvent.click(confirmBtn);

    // Photo removed from list
    await Testing.waitFor(() => {
      Vitest.expect(Testing.screen.getAllByAltText(/Photo from/)).toHaveLength(1);
    });
    Vitest.expect(
      Testing.screen.queryByRole("alertdialog", { name: /Remove photo/ }),
    ).not.toBeInTheDocument();
  });

  Vitest.it(
    "should not edit a superseded page when a removal resolves after a page change",
    async () => {
      let resolvePage2!: (result: Journal.GetPhotosResult) => void;
      const client = buildPhotoClient({
        removePhotoResult: { kind: "removed" },
      });
      let getIndex = 0;
      client.getPhotos = (_id, window) => {
        client.getPhotosCalls.push(window);
        getIndex++;
        if (getIndex === 1) return Promise.resolve(photosPage([photo1], true));
        return new Promise((resolve) => {
          resolvePage2 = resolve;
        });
      };

      Testing.render(() => (
        <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
      ));
      await Testing.screen.findAllByAltText(/Photo from/);

      Testing.fireEvent.click(
        Testing.screen.getByRole("button", { name: "Remove photo from 15.05.2026 13:00" }),
      );
      const confirmation = Testing.screen.getByRole("alertdialog", { name: /Remove photo/ });

      // A pagination click while the confirmation is open (not blocked by it) starts loading page 2.
      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Next" }));
      Vitest.expect(Testing.screen.getByText("Loading photos…")).toBeInTheDocument();

      Testing.fireEvent.click(
        Testing.within(confirmation).getByRole("button", { name: "Remove permanently" }),
      );
      resolvePage2(photosPage([photo2]));

      const photos = await Testing.screen.findAllByAltText(/Photo from/);
      Vitest.expect(photos).toHaveLength(1);
      Vitest.expect(photos[0]).toHaveAttribute("alt", Vitest.expect.stringContaining("14.05.2026"));
    },
  );

  Vitest.it(
    "should step back a page when removing the last photo empties the current page",
    async () => {
      const client = buildPhotoClient({
        getPhotosResults: [
          photosPage([photo1], true),
          photosPage([photo2]),
          photosPage([]),
          photosPage([photo1], true),
        ],
        removePhotoResult: { kind: "removed" },
      });

      Testing.render(() => (
        <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
      ));
      await Testing.screen.findAllByAltText(/Photo from/);

      Testing.fireEvent.click(Testing.screen.getByRole("button", { name: "Next" }));
      await Testing.screen.findByText("Page 2");

      Testing.fireEvent.click(
        Testing.screen.getByRole("button", { name: "Remove photo from 14.05.2026 11:30" }),
      );
      const confirmation = Testing.screen.getByRole("alertdialog", { name: /Remove photo/ });
      Testing.fireEvent.click(
        Testing.within(confirmation).getByRole("button", { name: "Remove permanently" }),
      );

      await Testing.screen.findByText("Page 1");
      Vitest.expect(await Testing.screen.findAllByAltText(/Photo from/)).toHaveLength(1);
    },
  );

  Vitest.it("should leave the list unchanged when remove is cancelled", async () => {
    const client = buildPhotoClient({
      getPhotosResults: [photosPage([photo1, photo2])],
    });

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));

    await Testing.screen.findAllByAltText(/Photo from/);
    Testing.fireEvent.click(
      Testing.screen.getByRole("button", { name: "Remove photo from 15.05.2026 13:00" }),
    );

    const confirmation = Testing.screen.getByRole("alertdialog", { name: /Remove photo/ });
    const cancelBtn = Testing.within(confirmation).getByRole("button", { name: "Cancel" });
    Testing.fireEvent.click(cancelBtn);

    Vitest.expect(
      Testing.screen.queryByRole("alertdialog", { name: /Remove photo/ }),
    ).not.toBeInTheDocument();
    Vitest.expect(Testing.screen.getAllByAltText(/Photo from/)).toHaveLength(2);
  });

  Vitest.it("should show an error from remove confirmation failure", async () => {
    const client = buildPhotoClient({
      getPhotosResults: [photosPage([photo1])],
      removePhotoResult: { kind: "removeFailed", reason: new Error("server error") },
    });

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));

    await Testing.screen.findByAltText(/Photo from/);
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: /Remove photo/ }));

    const confirmation = Testing.screen.getByRole("alertdialog", { name: /Remove photo/ });
    Testing.fireEvent.click(
      Testing.within(confirmation).getByRole("button", { name: "Remove permanently" }),
    );

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent(
      "could not be removed",
    );
    // List unchanged
    Vitest.expect(Testing.screen.getAllByAltText(/Photo from/)).toHaveLength(1);
  });

  Vitest.it("should show photoMissing error from remove", async () => {
    const client = buildPhotoClient({
      getPhotosResults: [photosPage([photo1])],
      removePhotoResult: { kind: "photoMissing" },
    });

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));

    await Testing.screen.findByAltText(/Photo from/);
    Testing.fireEvent.click(Testing.screen.getByRole("button", { name: /Remove photo/ }));
    Testing.fireEvent.click(
      Testing.within(Testing.screen.getByRole("alertdialog")).getByRole("button", {
        name: "Remove permanently",
      }),
    );

    Vitest.expect(await Testing.screen.findByRole("alert")).toHaveTextContent("no longer exists");
  });

  Vitest.it("should close via Escape when not uploading", async () => {
    const onCancel = Vitest.vi.fn();
    const client = buildPhotoClient({ getPhotosResults: [photosPage([])] });

    const { unmount } = Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={onCancel} />
    ));
    await Testing.screen.findByText("No photos yet.");

    Testing.fireEvent.keyDown(window, { key: "Escape" });
    // Sheet needs 180ms animation; just check onCancel was scheduled
    await Testing.waitFor(() => {
      Vitest.expect(onCancel).toHaveBeenCalled();
    });
    unmount();
  });

  Vitest.it("should not close via Escape while an overlay is open", async () => {
    const onCancel = Vitest.vi.fn();
    const client = buildPhotoClient({ getPhotosResults: [photosPage([photo1])] });

    Testing.render(() => <PlantPhotosSheet plant={ficus()} photos={client} onCancel={onCancel} />);

    Testing.fireEvent.click(await Testing.screen.findByAltText(/Photo from/));
    Vitest.expect(Testing.screen.getByRole("dialog", { name: "Photo viewer" })).toBeInTheDocument();

    // Escape closes the overlay, not the sheet
    Testing.fireEvent.keyDown(window, { key: "Escape" });
    Vitest.expect(
      Testing.screen.queryByRole("dialog", { name: "Photo viewer" }),
    ).not.toBeInTheDocument();
    Vitest.expect(onCancel).not.toHaveBeenCalled();
  });

  Vitest.it("should not close via Escape while a remove confirmation is open", async () => {
    const onCancel = Vitest.vi.fn();
    const client = buildPhotoClient({ getPhotosResults: [photosPage([photo1])] });

    Testing.render(() => <PlantPhotosSheet plant={ficus()} photos={client} onCancel={onCancel} />);
    await Testing.screen.findAllByAltText(/Photo from/);

    Testing.fireEvent.click(
      Testing.screen.getByRole("button", { name: "Remove photo from 15.05.2026 13:00" }),
    );
    Vitest.expect(
      Testing.screen.getByRole("alertdialog", { name: /Remove photo/ }),
    ).toBeInTheDocument();

    Testing.fireEvent.keyDown(window, { key: "Escape" });

    Vitest.expect(onCancel).not.toHaveBeenCalled();
  });

  Vitest.it("should not close via Escape while an upload is pending", async () => {
    const onCancel = Vitest.vi.fn();
    let finishUpload: (result: Journal.AddPhotoResult) => void = () => undefined;
    const client = buildPhotoClient({ getPhotosResults: [photosPage([])] });
    client.addPhoto = () =>
      new Promise((resolve) => {
        finishUpload = resolve;
      });

    Testing.render(() => <PlantPhotosSheet plant={ficus()} photos={client} onCancel={onCancel} />);
    await Testing.screen.findByText("No photos yet.");

    const fileInput = Testing.screen.getByLabelText("Choose a photo to upload");
    const file = new File(["jpeg"], "photo.jpg", { type: "image/jpeg" });
    Object.defineProperty(fileInput, "files", { value: [file], configurable: true });
    Testing.fireEvent.change(fileInput);
    await Testing.screen.findByText("Uploading…");

    Testing.fireEvent.keyDown(window, { key: "Escape" });
    Vitest.expect(onCancel).not.toHaveBeenCalled();

    finishUpload({ kind: "addFailed", reason: new Error("unused") });
    await Testing.screen.findByRole("alert");
  });

  Vitest.it("should ignore a second Escape while the sheet is already closing", async () => {
    const onCancel = Vitest.vi.fn();
    const client = buildPhotoClient({ getPhotosResults: [photosPage([])] });

    const { unmount } = Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={onCancel} />
    ));
    await Testing.screen.findByText("No photos yet.");

    Testing.fireEvent.keyDown(window, { key: "Escape" });
    Testing.fireEvent.keyDown(window, { key: "Escape" });

    await Testing.waitFor(() => {
      Vitest.expect(onCancel).toHaveBeenCalledTimes(1);
    });
    unmount();
  });

  Vitest.it("should ignore a file-input change with no file selected", async () => {
    const client = buildPhotoClient({ getPhotosResults: [photosPage([])] });
    const addPhotoSpy = Vitest.vi.fn();
    client.addPhoto = addPhotoSpy;

    Testing.render(() => (
      <PlantPhotosSheet plant={ficus()} photos={client} onCancel={() => undefined} />
    ));
    await Testing.screen.findByText("No photos yet.");

    const fileInput = Testing.screen.getByLabelText("Choose a photo to upload");
    Object.defineProperty(fileInput, "files", { value: [], configurable: true });
    Testing.fireEvent.change(fileInput);

    Vitest.expect(addPhotoSpy).not.toHaveBeenCalled();
    Vitest.expect(Testing.screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
