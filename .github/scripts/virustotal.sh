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
# Upload, retrying when the free API's 4 requests a minute are used up (another scan running).
id=""
for attempt in 1 2 3 4; do
  id=$(curl -sfS -H "x-apikey: ${VT_KEY}" -F "file=@${jar}" "${api}/files" | jq -r '.data.id // empty') && [ -n "${id}" ] && break
  echo "VirusTotal upload refused (attempt ${attempt}); retrying in 30 seconds"
  sleep 30
done
if [ -z "${id}" ]; then
  echo "::error::Could not submit the jar to VirusTotal"
  exit 1
fi
sha=$(sha256sum "${jar}" | cut -d' ' -f1)
# The free API allows 4 requests a minute per key. Poll every 30 seconds (2 a minute), so a pull
# request scan and a release scan running at the same time still fit; a refused poll is retried at
# the next one. Up to 15 minutes.
status=""
analysis="{}"
for i in $(seq 30); do
  sleep 30
  reply=$(curl -sfS -H "x-apikey: ${VT_KEY}" "${api}/analyses/${id}" || true)
  [ -n "${reply}" ] || continue
  analysis="${reply}"
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
