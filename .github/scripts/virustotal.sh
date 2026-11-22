#!/usr/bin/env bash
# Scans a jar with VirusTotal's engines: .github/scripts/virustotal.sh <jar>. Needs VT_KEY (the
# VIRUSTOTAL_API_KEY secret); skipped without it, as on pull requests from forks. Fails when two
# or more engines flag the jar as malicious; a single detection is usually a false positive on a
# new jar, so that only warns. The report link goes to the job summary.
set -euo pipefail
if [ -z "${VT_KEY:-}" ]; then
  echo "::notice::VIRUSTOTAL_API_KEY is not available; skipping the VirusTotal scan"
  exit 0
fi
jar="$1"
api=https://www.virustotal.com/api/v3
sha=$(sha256sum "${jar}" | cut -d' ' -f1)
# Upload, retrying when the free API's 4 requests a minute are used up (another scan running).
# 409: VirusTotal already has this exact file and is still analysing it (a rebuild that changed
# nothing in the jar, such as a changelog-only commit): read that file's report instead.
id=""
existing=false
for attempt in 1 2 3 4; do
  code=$(curl -sS -o /tmp/vt-upload.json -w '%{http_code}' -H "x-apikey: ${VT_KEY}" -F "file=@${jar}" "${api}/files" || echo 000)
  if [ "${code}" = 200 ]; then
    id=$(jq -r '.data.id // empty' /tmp/vt-upload.json)
    [ -n "${id}" ] && break
  elif [ "${code}" = 409 ]; then
    echo "VirusTotal already has this jar (${sha}); using its report"
    existing=true
    break
  fi
  echo "VirusTotal upload refused (HTTP ${code}, attempt ${attempt}); retrying in 30 seconds"
  sleep 30
done
if [ -z "${id}" ] && [ "${existing}" != true ]; then
  echo "::error::Could not submit the jar to VirusTotal"
  exit 1
fi
# The free API allows 4 requests a minute per key. Poll every 30 seconds (2 a minute), so a pull
# request scan and a release scan running at the same time still fit; a refused poll is retried at
# the next one. Up to 15 minutes.
status=""
analysis="{}"
for i in $(seq 30); do
  sleep 30
  if [ "${existing}" = true ]; then
    # The file's own report: its last analysis, once there is one.
    reply=$(curl -sfS -H "x-apikey: ${VT_KEY}" "${api}/files/${sha}" || true)
    [ -n "${reply}" ] || continue
    analysis=$(echo "${reply}" | jq -c '{data: {attributes: {stats: .data.attributes.last_analysis_stats,
      status: (if (.data.attributes.last_analysis_date // 0) > 0 then "completed" else "queued" end)}}}')
  else
    reply=$(curl -sfS -H "x-apikey: ${VT_KEY}" "${api}/analyses/${id}" || true)
    [ -n "${reply}" ] || continue
    analysis="${reply}"
  fi
  status=$(echo "${analysis}" | jq -r '.data.attributes.status // empty')
  # Partial results already over the threshold are enough to stop.
  [ "${status}" = completed ] && break
  [ "$(echo "${analysis}" | jq -r '.data.attributes.stats.malicious // 0')" -ge 2 ] && break
done
stats=$(echo "${analysis}" | jq -c '.data.attributes.stats // {}')
malicious=$(echo "${stats}" | jq -r '.malicious // 0')
suspicious=$(echo "${stats}" | jq -r '.suspicious // 0')
link="https://www.virustotal.com/gui/file/${sha}"
{
  echo "### VirusTotal: $(basename "${jar}")"
  echo "Malicious: ${malicious}, suspicious: ${suspicious} ([report](${link}))"
} >> "${GITHUB_STEP_SUMMARY}"
# The threshold first: partial results of an unfinished analysis count too.
if [ "${malicious}" -ge 2 ]; then
  echo "::error::${malicious} VirusTotal engines flag the jar as malicious: ${link}"
  exit 1
elif [ "${status}" != completed ]; then
  echo "::warning::VirusTotal analysis did not finish in time (${malicious} malicious so far): ${link}"
elif [ "${malicious}" -ge 1 ] || [ "${suspicious}" -ge 1 ]; then
  echo "::warning::VirusTotal: ${malicious} malicious, ${suspicious} suspicious (likely false positives): ${link}"
else
  echo "VirusTotal: clean (${stats}) ${link}"
fi
