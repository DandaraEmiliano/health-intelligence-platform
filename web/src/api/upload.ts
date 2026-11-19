import { config } from "../config";
import { ApiProblemError, isProblem } from "./client";
import type { HealthDocument } from "./resources";
import { tokenStore } from "./tokenStore";

/**
 * Uploads one PDF with progress. XMLHttpRequest because fetch cannot report upload progress.
 * The Idempotency-Key lets a retry after a network error return the first result instead of
 * creating a second document (ADR 0009).
 */
export function uploadDocument(
  file: File,
  idempotencyKey: string,
  onProgress: (fraction: number) => void,
  xhrFactory: () => XMLHttpRequest = () => new XMLHttpRequest(),
): Promise<HealthDocument> {
  return new Promise((resolve, reject) => {
    const xhr = xhrFactory();
    xhr.open("POST", `${config.apiBaseUrl}/api/v1/documents`);
    const token = tokenStore.get();
    if (token) xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    xhr.setRequestHeader("Idempotency-Key", idempotencyKey);
    xhr.setRequestHeader("Accept", "application/json, application/problem+json");
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(event.loaded / event.total);
    };
    xhr.onload = () => {
      let body: unknown;
      try {
        body = xhr.responseText ? JSON.parse(xhr.responseText) : undefined;
      } catch {
        body = undefined;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(body as HealthDocument);
      } else if (isProblem(body)) {
        reject(new ApiProblemError(body));
      } else {
        reject(new ApiProblemError({ type: "about:blank", title: "Error", status: xhr.status, code: "HTTP_ERROR", correlationId: "" }));
      }
    };
    xhr.onerror = () => reject(new TypeError("network error"));
    const form = new FormData();
    form.append("file", file, file.name);
    xhr.send(form);
  });
}
