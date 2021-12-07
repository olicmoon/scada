#!/bin/bash

set -euo pipefail
shopt -s nullglob
shopt -s inherit_errexit

# usage register-datasources.sh DB_LOCATION DS_NAME DS_URL DS_USER DS_PASSWD
#   ie: register-datasources.sh /var/lib/ignition/data/db/config.idb BinConvData scada.bowery.com:5551/ignition bowery passwd
DB_LOCATION="${1}"
DS_NAME="${2}"
DS_URL="${3}"
DS_USER="${4}"
DS_PASSWD="${5}"

DB_FILE=$(basename "${DB_LOCATION}")

function main() {
    if [ ! -f "${DB_LOCATION}" ]; then
        echo "init     | WARNING: ${DB_FILE} not found, skipping datasource registration"
        return 0
    fi

    register_datasources
}

function register_datasources() {
    local SQLITE3=( sqlite3 -cmd ".timeout 1000" "${DB_LOCATION}" )

    echo "init     | Registering datasources..."

    local ds_tbl ds_name ds_url ds_user
    ds_tbl="DATASOURCES"
    ds_name=${DS_NAME}
    ds_url="jdbc:postgresql://${DS_URL}"
    ds_user=${DS_USER}
    ds_passwd=${DS_PASSWD}
    # Ignition internally encrypt the password with a hardcoded key
    # Looks ok for dev environment
    # TODO: in production we shall pull out the hardcoded key and actually encrypt the given password
    #       otherwise, passwords can be manucally updated during production deployment
    ds_passwd_enc="ea0d6c27faecfcbfdd3fb6682a8ebc68"
    ds_enabled=1

    local already_exists
    already_exists=$("${SQLITE3[@]}" "SELECT 1 FROM ${ds_tbl} WHERE NAME = '${ds_name}'")
    if [ "${already_exists}" != "1" ]; then
        local next_id
        next_id=$("${SQLITE3[@]}" "SELECT COALESCE(MAX(DATASOURCES_ID)+1, 11) FROM ${ds_tbl}")
        echo "init     | Inserting ${ds_name}"
        "${SQLITE3[@]}" "INSERT INTO ${ds_tbl} (DATASOURCES_ID, NAME, DRIVERID, TRANSLATORID, CONNECTURL, USERNAME, PASSWORD, PASSWORDE, ENABLED) VALUES (${next_id}, '${ds_name}', 3, 3, '${ds_url}', '${ds_user}', '${ds_passwd}', '${ds_passwd_enc}', ${ds_enabled})"
    else
        echo "init     | Skip inserting ${ds_name} (already exists)"
    fi
}

main
