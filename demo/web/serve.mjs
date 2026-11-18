#!/usr/bin/env node
// A static file server for trying the web page on your own machine:
//
//   node serve.mjs [folder] [port]        (the folder this file is in, port 8338)
//
// The page's Java runtime reads the jar beside it piece by piece, with HTTP Range requests. Many one-line servers
// (Python's http.server among them) ignore the Range header and send the whole file each time, and the page then
// never starts. This one answers them. Any real web host does too (GitHub Pages, nginx, S3).
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml",
  ".jar": "application/java-archive",
};

/** Serves `folder` on 127.0.0.1:`port` (0 picks a free one). Resolves to the server once it listens. */
export function serve(folder, port) {
  const root = path.resolve(folder);
  const server = http.createServer((request, response) => {
    const asked = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
    let file = path.join(root, asked);
    if (file !== root && !file.startsWith(root + path.sep)) return void response.writeHead(403).end();
    if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, "index.html");
    if (!fs.existsSync(file)) return void response.writeHead(404).end("not found");
    const size = fs.statSync(file).size;
    const headers = {
      "Content-Type": types[path.extname(file)] || "application/octet-stream",
      "Accept-Ranges": "bytes",
      "Cache-Control": "no-cache",
    };
    const range = /^bytes=(\d*)-(\d*)$/.exec(request.headers.range || "");
    if (range && (range[1] || range[2])) {
      const start = range[1] ? Number(range[1]) : Math.max(0, size - Number(range[2]));
      const end = range[1] && range[2] ? Math.min(Number(range[2]), size - 1) : size - 1;
      if (start > end || start >= size) {
        return void response.writeHead(416, { "Content-Range": `bytes */${size}` }).end();
      }
      response.writeHead(206, { ...headers, "Content-Range": `bytes ${start}-${end}/${size}`, "Content-Length": end - start + 1 });
      if (request.method === "HEAD") return void response.end();
      return void fs.createReadStream(file, { start, end }).pipe(response);
    }
    response.writeHead(200, { ...headers, "Content-Length": size });
    if (request.method === "HEAD") return void response.end();
    fs.createReadStream(file).pipe(response);
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const folder = process.argv[2] || path.dirname(fileURLToPath(import.meta.url));
  const server = await serve(folder, Number(process.argv[3] || 8338));
  console.log(`TDA4j web demo: http://127.0.0.1:${server.address().port}/  (serving ${path.resolve(folder)}, Ctrl-C stops it)`);
}
