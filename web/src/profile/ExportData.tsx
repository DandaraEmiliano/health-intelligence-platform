import { IconDownload } from "@tabler/icons-react";
import { useMutation } from "@tanstack/react-query";

import { problemMessage } from "../api/messages";
import { accountApi } from "../api/resources";

/** Saves the export as a file. The JSON stays in memory only for the moment of the download. */
function save(data: unknown, filename: string) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export function ExportData() {
  const download = useMutation({
    mutationFn: accountApi.exportData,
    onSuccess: (data) => save(data, `meus-dados-${data.generatedAt.slice(0, 10)}.json`),
  });

  return (
    <div className="card">
      <h2>Baixar meus dados</h2>
      <p className="muted">
        Um arquivo JSON com tudo o que guardamos sobre você: perfil, consentimentos, alergias, remédios, condições,
        laudos com todos os resultados, conversas com o assistente e avisos. Os PDFs enviados podem ser baixados em Documentos.
      </p>
      {download.isError && <p className="error-box" role="alert">{problemMessage(download.error)}</p>}
      {download.isSuccess && <p className="success-box" role="status">Arquivo gerado. Guarde-o em um lugar seguro: ele tem dados de saúde.</p>}
      <div>
        <button onClick={() => download.mutate()} aria-busy={download.isPending} disabled={download.isPending}>
          <IconDownload size={16} aria-hidden="true" /> Baixar meus dados
        </button>
      </div>
    </div>
  );
}
