#!/bin/bash

set -euo pipefail
shopt -s nullglob
shopt -s inherit_errexit

# usage register-tagproviders.sh DB_LOCATION
#   ie: register-tagproviders.sh /var/lib/ignition/data/db/config.idb
DB_LOCATION="${1}"

DB_FILE=$(basename "${DB_LOCATION}")

function main() {
    if [ ! -f "${DB_LOCATION}" ]; then
        echo "init     | WARNING: ${DB_FILE} not found, skipping device registration"
        return 0
    fi

    register_tagprovider "Public" "ef416b7d-4dd5-4310-843a-aa9e16ff3a1d" "Bowery SCADA Public Tags"
    register_tagprovider "Simulator" "584b5b7c-4771-4cf7-9baa-c60ab984e57c" "Bowery Simulator Tags For Test"
}

function register_tagprovider() {
    local SQLITE3=( sqlite3 -cmd ".timeout 1000" "${DB_LOCATION}" )

    local tbl="TAGPROVIDERSETTINGS"
    local name="${1}"
    local uuid="${2}"
    local desc="${3}"
    local enabled=1
    local typeid="STANDARD"
    local allowbackfill=0

    local already_exists
    already_exists=$("${SQLITE3[@]}" "SELECT 1 FROM ${tbl} WHERE NAME = '${name}'")
    if [ "${already_exists}" != "1" ]; then
        local next_id
        next_id=$("${SQLITE3[@]}" "SELECT COALESCE(MAX(TAGPROVIDERSETTINGS_ID)+1, 2) FROM ${tbl}")
        next_id=$((next_id>2 ? next_id:2))
        echo "init     | Registering tag provider ${name}"
        "${SQLITE3[@]}" "INSERT INTO ${tbl} (TAGPROVIDERSETTINGS_ID, NAME, PROVIDERID, DESCRIPTION, ENABLED, TYPEID, ALLOWBACKFILL) VALUES (${next_id}, '${name}', '${uuid}', '${desc}', ${enabled}, '${typeid}', ${allowbackfill})"
    else
        echo "init     | Skip registering tag provider ${name} (already exists)"
    fi
}

main
