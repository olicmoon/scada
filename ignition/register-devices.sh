#!/bin/bash

set -euo pipefail
shopt -s nullglob
shopt -s inherit_errexit

# usage register-devices.sh DB_LOCATION FARM_ID
#   ie: register-devices.sh /var/lib/ignition/data/db/config.idb F2
DB_LOCATION="${1}"
FARM_ID="${2}"

DB_FILE=$(basename "${DB_LOCATION}")

function main() {
    if [ ! -f "${DB_LOCATION}" ]; then
        echo "init     | WARNING: ${DB_FILE} not found, skipping device registration"
        return 0
    fi

    register_devices
}

function register_devices() {
    local SQLITE3=( sqlite3 -cmd ".timeout 1000" "${DB_LOCATION}" )

    echo "init     | Registering devices..."

    local dev_name dev_type dev_desc dev_enabled
    dev_tbl="DEVICESETTINGS"
    dev_name="Bowery SCADA Simulator"
    dev_type="SimulatorDevice"
    dev_desc="Provides simulated test environment"
    dev_enabled=1

    local already_exists
    already_exists=$("${SQLITE3[@]}" "SELECT 1 FROM ${dev_tbl} WHERE NAME = '${dev_name}'")
    if [ "${already_exists}" != "1" ]; then
        local next_id
        next_id=$("${SQLITE3[@]}" "SELECT COALESCE(MAX(DEVICESETTINGS_ID)+1, 11) FROM ${dev_tbl}")
        next_id=$((next_id>11 ? next_id:11))
        echo "init     | Inserting ${dev_name}"
        "${SQLITE3[@]}" "INSERT INTO ${dev_tbl} (DEVICESETTINGS_ID, NAME, TYPE, DESCRIPTION, ENABLED) VALUES (${next_id}, '${dev_name}', '${dev_type}', '${dev_desc}', ${dev_enabled})"
    else
        echo "init     | Skip inserting ${dev_name} (already exists)"
    fi

    local dev_id bowery_dev_tbl
    dev_id=$("${SQLITE3[@]}" "SELECT DEVICESETTINGS_ID FROM ${dev_tbl} WHERE NAME = '${dev_name}'")
    bowery_dev_tbl="BOWERYSCADADEVICESETTINGS"

    already_exists=$("${SQLITE3[@]}" "SELECT EXISTS (SELECT * FROM sqlite_master WHERE type='table' and name='${bowery_dev_tbl}')")
    if [ "${already_exists}" != "1" ];then
        "${SQLITE3[@]}" "CREATE TABLE ${bowery_dev_tbl} (DEVICESETTINGSID NUMERIC(18,0) NOT NULL, FARMCODE VARCHAR(4096) NOT NULL, CONSTRAINT PK_BOWERYSCADADEVICESETTINGS PRIMARY KEY (DEVICESETTINGSID))"
        "${SQLITE3[@]}" "INSERT INTO ${bowery_dev_tbl} (DEVICESETTINGSID, FARMCODE) VALUES (${dev_id}, '${FARM_ID}')"
    fi
}

main
