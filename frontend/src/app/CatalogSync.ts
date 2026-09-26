import type { Setter } from "solid-js";

export const appendEntry = <A>(setList: Setter<readonly A[]>, entry: A): void => {
  setList((current) => [...current, entry]);
};

export const replaceEntry = <A, Id>(
  setList: Setter<readonly A[]>,
  id: Id,
  getId: (entry: A) => Id,
  entry: A,
): void => {
  setList((current) => current.map((existing) => (getId(existing) === id ? entry : existing)));
};
