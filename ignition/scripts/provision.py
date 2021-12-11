import os, re, enum, traceback
import json

import sqlite3
import shutil
import xmltodict

from common import logger, restart_ignition
from common import IGNITION_INSTALL_LOCATION, PROVISION_CACHE_PATH, IGNITION_UID, IGNITION_GID
from common import CONFIG_VOLUME_PATH

class ProvisionStatus(enum.Enum):
    UNKNOWN = 'UNKNOWN'
    PROVISIONED = 'PROVISIONED'

class DeploymentType(enum.Enum):
    DEV = 'DEV'
    STAGING = 'STAGING'
    PROD = 'PROD'

def store_provision_cache(deployment: DeploymentType, status: ProvisionStatus):
    cache = dict()
    cache['deployment'] = deployment.value
    cache['status'] = status.value

    cache_file = f'{IGNITION_INSTALL_LOCATION}/{PROVISION_CACHE_PATH}'
    if os.path.exists(cache_file):
        os.remove(cache_file)

    with open(cache_file, 'w') as fp:
        json.dump(cache, fp)
        fp.flush()

def read_provisioning_status() -> ProvisionStatus:
    cache_file = f'{IGNITION_INSTALL_LOCATION}/{PROVISION_CACHE_PATH}'
    if os.path.exists(cache_file):
        with open(cache_file, 'r') as fp:
            cache = json.load(fp)
            if 'status' in cache:
                return ProvisionStatus(cache['status'])

    return ProvisionStatus.UNKNOWN

def register_modules(db_path: str) -> bool:
    db_conn = None
    try:
        db_conn = sqlite3.connect(db_path)

        module_dir = '/modules'
        for _, _, files in os.walk(module_dir):
            for fname in files:
                if not fname.endswith('.modl'):
                    continue

                module_path = f'{module_dir}/{fname}'
                module_install_path = f'{IGNITION_INSTALL_LOCATION}/user-lib/modules/{fname}'
                shutil.copy(module_path, module_install_path)
                os.chown(module_install_path, IGNITION_UID, IGNITION_GID)
                logger.info(f'Installing {module_path}')

                module_info = xmltodict.parse(os.popen(f'unzip -qq -c "{module_path}" module.xml').read())
                module_id = module_info['modules']['module']['id']

                license_crc32 = os.popen(f'unzip -qq -c "{module_path}" license.html \
                        | gzip -c | tail -c8 | od -t u4 -N 4 -A n | cut -c 2-').read().strip()

                keytool_path = os.popen('which keytool').read().strip()
                cert_info = os.popen(f'unzip -qq -c {module_path} certificates.p7b | \
                        {keytool_path} -printcert -v | head -n 9').read().strip()

                subject_name = ''
                thumbprint = ''
                fingerprints_line = 0
                linenum = 0
                for line in cert_info.split('\n'):
                    linenum = linenum + 1
                    # print(f'[{linenum}] {line}')
                    if 'Owner' in line:
                        m = re.search('CN=(.+?),', line)
                        subject_name = m.group(1)

                    if 'Certificate fingerprints' in line:
                        fingerprints_line = linenum

                    if fingerprints_line > 0 and 'SHA1:' in line:
                        arr = line.split('SHA1: ')
                        if len(arr) == 2:
                            thumbprint = arr[1].replace(':', '').lower()

                logger.info(f'       Module: {module_id} {fname}')
                logger.info(f'   Thumbprint: {thumbprint}')
                logger.info(f' Subject Name: {subject_name}')
                logger.info(f'License crc32: {license_crc32}')

                cur = db_conn.cursor()

                cur.execute(f'SELECT 1 FROM CERTIFICATES WHERE lower(hex(THUMBPRINT)) = "{thumbprint}"')
                rows = cur.fetchall()
                thumbprint_already_exists = len(rows) != 0

                if not thumbprint_already_exists:
                    cur.execute('SELECT COALESCE(MAX(CERTIFICATES_ID)+1,1) FROM CERTIFICATES')
                    rows = cur.fetchall()
                    cert_id = rows[0][0]
                    logger.info(f'Insert thumbprint {cert_id}')
                    cur.executescript(f'INSERT INTO CERTIFICATES (CERTIFICATES_ID, THUMBPRINT, SUBJECTNAME) \
                            VALUES ({cert_id}, x\'{thumbprint}\', "{subject_name}"); \
                            UPDATE SEQUENCES SET val={cert_id} WHERE name="CERTIFICATES_SEQ"')

                cur.execute(f'SELECT 1 FROM EULAS WHERE MODULEID="{module_id}" AND CRC="{license_crc32}"')
                rows = cur.fetchall()
                module_id_already_exists = len(rows) != 0
                if not module_id_already_exists:
                    cur.execute('SELECT COALESCE(MAX(EULAS_ID)+1,1) FROM EULAS')
                    rows = cur.fetchall()
                    eula_id = rows[0][0]

                    logger.info(f'Accepting EULA id:{module_id} {eula_id}')
                    cur.executescript(f'INSERT INTO EULAS (EULAS_ID, MODULEID, CRC) \
                            VALUES ({eula_id}, "{module_id}", "{license_crc32}"); \
                            UPDATE SEQUENCES SET val={eula_id} WHERE name="EULAS_SEQ"')

                cur.close()

        return True
    except Exception as e:
        logger.error(f'Failed to register modules {db_path}: {e}')
        logger.error(traceback.print_exc())
        return False
    finally:
        if db_conn:
            db_conn.commit()
            db_conn.close()

def register_devices(db_path: str) -> bool:
    db_config_path = f'{CONFIG_VOLUME_PATH}/db/devices'
    if not os.path.exists(db_config_path):
        logger.error(f'Database configuration path not available: {db_config_path}')
        return False

    db_conn = None
    try:
        # okay to copy entire table to memory but care if we're doing this for larger table than 
        # Ignition configs..
        db_conn = sqlite3.connect(db_path)
        for _, _, files in os.walk(db_config_path):
            for file in files:
                if not file.endswith('.sql'):
                    continue
                tbl_name = os.path.splitext(file)[0]

                with open(f'{db_config_path}/{file}', 'r') as fp:
                    cur = db_conn.cursor()
                    cur.execute(f'SELECT EXISTS (SELECT * FROM sqlite_master WHERE type="table" and name="{tbl_name}")')
                    row = cur.fetchone()
                    if row[0] == 1:
                        cur.execute(f'drop table {tbl_name}')
                    cur.executescript(''.join(fp.readlines()))
                    db_conn.commit()

        return True
    except Exception as e:
        logger.error(f'Failed to register devices: {e}')
        logger.error(traceback.print_exc())
        return False
    finally:
        if db_conn:
            db_conn.close()

def register_tagprovider(db_path: str, name: str, uuid: str, desc: str,
        enabled: bool = True, typeid:str = "STANDARD") -> bool:
    db_conn = None
    try:
        db_conn = sqlite3.connect(db_path)
        cur = db_conn.cursor()
        tbl = "TAGPROVIDERSETTINGS"
        cur.execute(f'SELECT 1 FROM {tbl} WHERE NAME = "{name}"')
        rows = cur.fetchall()
        if len(rows) != 1:
            logger.info('Register tag provider [Public]')
            cur.execute(f'SELECT COALESCE(MAX(TAGPROVIDERSETTINGS_ID)+1, 2) FROM "{tbl}"')
            rows = cur.fetchall()
            next_id = rows[0][0]
            cur.execute(f'INSERT INTO {tbl} \
                    (TAGPROVIDERSETTINGS_ID, NAME, PROVIDERID, DESCRIPTION, ENABLED, TYPEID, ALLOWBACKFILL) \
                    VALUES ({next_id}, "{name}", "{uuid}", "{desc}", {enabled}, "{typeid}", 0)')
        else:
            logger.info("Skip registering tag provider ${name} (already exists)")

        return True
    except Exception as e:
        logger.error(f'Failed to register tag provider {db_path}: {e}')
        logger.error(traceback.print_exc())
        return False
    finally:
        if db_conn:
            db_conn.commit()
            db_conn.close()

def register_datasources(db_path: str) -> bool:
    db_conn = None
    try:
        db_conn = sqlite3.connect(db_path)

        return True
    except Exception as e:
        logger.error(f'Failed to register datasources {db_path}: {e}')
        logger.error(traceback.print_exc())
        return False
    finally:
        if db_conn:
            db_conn.commit()
            db_conn.close()

def perform_provisioning(deployment: DeploymentType) -> bool:
    db_path = f'{IGNITION_INSTALL_LOCATION}/data/db/config.idb'

    status = read_provisioning_status()
    if status != ProvisionStatus.PROVISIONED:
        logger.info('Start provisioning..')

        logger.info('Register modules..')
        if not register_modules(db_path):
            return False

        # if not register_jdbc_driver(db_path):
        #    return False

        logger.info('Register devices..')
        if not register_devices(db_path):
            return False

        logger.info('Register tag providers..')
        if not register_tagprovider(db_path=db_path, name="Public",
                uuid="ef416b7d-4dd5-4310-843a-aa9e16ff3a1d", desc="Bowery SCADA Public Tags"):
            return False

        if not register_tagprovider(db_path=db_path, name="Simulator",
                uuid="584b5b7c-4771-4cf7-9baa-c60ab984e57c", desc="Bowery Simulator Tags For Test"):
            return False

        # Restart ignition gateway to apply changes
        store_provision_cache(deployment, ProvisionStatus.PROVISIONED)
        logger.info('Finish provisioning.. restarting gateway..')
        restart_ignition()
        return True
    else:
        # Install latest module in /modules
        if not register_modules(db_path):
            return False

        return True

