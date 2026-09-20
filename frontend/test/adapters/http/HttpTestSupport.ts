export const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

export const respondingWith = (
  responses: (Response | Error)[],
  requests: Request[] = [],
): ((request: Request) => Promise<Response>) => {
  return (request) => {
    requests.push(request);
    const response = responses.shift();
    if (response instanceof Error) return Promise.reject(response);
    if (response === undefined) return Promise.reject(new Error("unexpected request"));
    return Promise.resolve(response);
  };
};
