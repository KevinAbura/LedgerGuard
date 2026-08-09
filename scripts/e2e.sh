#!/usr/bin/env bash
set -euo pipefail

base_url="${BASE_URL:-http://localhost:8080}"
api_key="${API_KEY:-ledgerguard-dev-key}"

for attempt in $(seq 1 60); do
  if curl --fail --silent "${base_url}/actuator/health/readiness" >/dev/null; then
    break
  fi
  if [[ "${attempt}" == "60" ]]; then
    echo "LedgerGuard did not become ready" >&2
    exit 1
  fi
  sleep 2
done

suffix="$(date +%s)-${RANDOM}"
asset_response="$(curl --fail --silent --show-error \
  -H "X-API-Key: ${api_key}" \
  -H "Content-Type: application/json" \
  -d "{\"externalId\":\"settlement-${suffix}\",\"currency\":\"USD\",\"accountType\":\"ASSET\",\"allowNegative\":true}" \
  "${base_url}/v1/accounts")"
liability_response="$(curl --fail --silent --show-error \
  -H "X-API-Key: ${api_key}" \
  -H "Content-Type: application/json" \
  -d "{\"externalId\":\"wallet-${suffix}\",\"currency\":\"USD\",\"accountType\":\"LIABILITY\",\"allowNegative\":false}" \
  "${base_url}/v1/accounts")"
asset_id="$(jq -r .id <<<"${asset_response}")"
liability_id="$(jq -r .id <<<"${liability_response}")"

payload="{\"externalReference\":\"deposit-${suffix}\",\"transactionType\":\"DEPOSIT\",\"description\":\"E2E deposit\",\"entries\":[{\"accountId\":\"${asset_id}\",\"side\":\"DEBIT\",\"amount\":100},{\"accountId\":\"${liability_id}\",\"side\":\"CREDIT\",\"amount\":100}]}"
headers="$(mktemp)"
trap 'rm -f "${headers}"' EXIT
posted="$(curl --fail --silent --show-error -D "${headers}" \
  -H "X-API-Key: ${api_key}" \
  -H "Idempotency-Key: deposit-idem-${suffix}" \
  -H "Content-Type: application/json" \
  -d "${payload}" \
  "${base_url}/v1/transactions")"
transaction_id="$(jq -r .id <<<"${posted}")"
test "$(grep -i '^Idempotent-Replayed:' "${headers}" | tr -d '\r' | awk '{print $2}')" = "false"

replayed="$(curl --fail --silent --show-error -D "${headers}" \
  -H "X-API-Key: ${api_key}" \
  -H "Idempotency-Key: deposit-idem-${suffix}" \
  -H "Content-Type: application/json" \
  -d "${payload}" \
  "${base_url}/v1/transactions")"
test "$(jq -r .id <<<"${replayed}")" = "${transaction_id}"
test "$(grep -i '^Idempotent-Replayed:' "${headers}" | tr -d '\r' | awk '{print $2}')" = "true"

wallet="$(curl --fail --silent --show-error \
  -H "X-API-Key: ${api_key}" \
  "${base_url}/v1/accounts/${liability_id}")"
jq -e '.balance == 100' <<<"${wallet}" >/dev/null

status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -H "X-API-Key: ${api_key}" \
  -H "Idempotency-Key: unbalanced-${suffix}" \
  -H "Content-Type: application/json" \
  -d "{\"externalReference\":\"bad-${suffix}\",\"transactionType\":\"BAD\",\"entries\":[{\"accountId\":\"${asset_id}\",\"side\":\"DEBIT\",\"amount\":5},{\"accountId\":\"${liability_id}\",\"side\":\"CREDIT\",\"amount\":4}]}" \
  "${base_url}/v1/transactions")"
test "${status}" = "409"

echo "LedgerGuard E2E passed: transaction ${transaction_id}"
