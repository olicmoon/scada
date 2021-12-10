#!/bin/bash python3

import os, time, shutil, enum
import json
import xmltodict
import re
import subprocess
import requests
import sqlite3

from threading import Thread
from typing import Optional, Tuple

IGNITION_INSTALL_LOCATION = os.getenv('IGNITION_INSTALL_LOCATION', None)
IGNITION_UID = 999
IGNITION_GID = 999
IGNITION_PORT = 8088
DATA_VOLUME_PATH = '/data'
PROVISION_CACHE_PATH ='data/bowery_provisioning.cache'

if IGNITION_INSTALL_LOCATION is None:
    raise ValueError("Undefined env: IGNITION_INSTALL_LOCATION")

INIT_FILE = "/usr/local/share/ignition/data/init.properties"
XML_FILE = f'{IGNITION_INSTALL_LOCATION}/data/gateway.xml_clean'


LOG_TAG = 'init     |'
class logger:
    @staticmethod
    def info(s):
        print(f'{LOG_TAG} INFO: {s}')

    @staticmethod
    def warn(s):
        print(f'{LOG_TAG} WARN: {s}')

    @staticmethod
    def error(s):
        print(f'{LOG_TAG} ERROR: {s}')

def build_ignition_cmd():
    command = "./ignition-gateway"
    command += " data/ignition.conf"
    command += " wrapper.syslog.ident=Ignition-Gateway"
    command += " wrapper.pidfile=./Ignition-Gateway.pid"
    command += " wrapper.name=Ignition-Gateway"
    command += " wrapper.displayname=Ignition-Gateway"
    command += " wrapper.statusfile=./Ignition-Gateway.status"
    command += " wrapper.java.statusfile=./Ignition-Gateway.java.status"
    command += " wrapper.console.loglevel=NONE"
    command += " wrapper.logfile.format=PTM"
    command += " wrapper.logfile.rollmode=NONE"
    return command

def health_check(desc: str, target: Tuple[str, Optional[str]],
        giveup: Optional[Tuple[str, Optional[str]]],
        delay: int = 180) -> bool:
    url = f'http://localhost:{IGNITION_PORT}/StatusPing'
    tn = time.time() + delay
    curr: Tuple[Optional[str], Optional[str]] = (None, None)
    while time.time() < tn:
        try:
            r = requests.get(url)
            if r.status_code == 200:
                msg = json.loads(r.text)
                state = msg['state']
                details = None
                if 'details' in msg:
                    details = msg['details']
                curr = (state, details)

                if curr == target:
                    return True

                if giveup is not None and curr == giveup:
                    return False
        except:
            pass
        time.sleep(1)
        logger.warn(f'{desc} health check.. retrying.. state:{curr[0]} (details:{curr[1]})')

    logger.error('{desc} health check failed: Timeout')
    return False

def copytree(src, dst, symlinks=False, ignore=None):
    for item in os.listdir(src):
        s = os.path.join(src, item)
        d = os.path.join(dst, item)
        if os.path.isdir(s):
            shutil.copytree(s, d, symlinks, ignore)
        else:
            shutil.copy2(s, d)

def prepare_data_volume():
    if not os.path.exists(f'{DATA_VOLUME_PATH}/db/config.idb'):
        copytree(f'{IGNITION_INSTALL_LOCATION}/data', f'{DATA_VOLUME_PATH}')

    if os.path.islink(f'{IGNITION_INSTALL_LOCATION}/data'):
        os.remove(f'{IGNITION_INSTALL_LOCATION}/data')
    else:
        shutil.rmtree(f'{IGNITION_INSTALL_LOCATION}/data')
    os.remove(f'{IGNITION_INSTALL_LOCATION}/webserver/metro-keystore')

    os.symlink(f'{DATA_VOLUME_PATH}', f'{IGNITION_INSTALL_LOCATION}/data')
    os.symlink(f'{DATA_VOLUME_PATH}/metro-keystore', f'{IGNITION_INSTALL_LOCATION}/webserver/metro-keystore')

    var_dir = '/var/lib/ignition'
    if os.path.islink(f'{var_dir}/data'):
        os.remove(f'{var_dir}/data')
    else:
        shutil.rmtree(f'{var_dir}/data')
    os.symlink(f'{DATA_VOLUME_PATH}', f'{var_dir}/data')

def perform_commissioning(auth_password: str,
        license_key: Optional[str] = None,
        activation_token: Optional[str] = None) -> bool:
    base_url = f'http://localhost:{IGNITION_PORT}'
    bootstrap_url = f'{base_url}/bootstrap'
    get_url = f'{base_url}/get-step'
    post_url = f'{base_url}/post-step'

    r = requests.get(bootstrap_url)
    if r.status_code != 200:
        logger.error(f'commissioning failed: {r.status_code} from bootstrap')
        return False
    body = json.loads(r.text)
    logger.info(f'bootstrap: {r.text}')
    edition = body['edition']
    if edition == 'NOT_SET':
        logger.info('perform commissioning.. edution selection')
        edition_selection_payload = {
                'id':'edition',
                'step':'edition',
                'data':{
                    'edition': "" # empty string means full edition.. somehow
                    }
                }

        r = requests.post(url=post_url, json=edition_selection_payload)
        if r.status_code != 201:
            logger.error(f'commissioning failed: {r.status_code} from edition selection')
            return False

    steps: dict = body['steps']
    if 'eula' in steps:
        logger.info('perform commissioning.. license acceptance')
        license_accept_payload={
                'id': 'license',
                'step': 'eula',
                'data': {
                    'accept':True
                    }
                }
        r = requests.post(url=post_url, json=license_accept_payload)
        if r.status_code != 201:
            logger.error(f'commissioning failed: {r.status_code} from license acceptance')
            return False

    if 'activated' in steps:
        logger.info('perform commissioning.. authentication activation')
        # TODO: revisit license activation process
        if license_key is not None and activation_token is not None:
            activation_payload = {
                    'id':'activation',
                    'data': {
                        'licenseKey': {license_key},
                        'activationToken': {activation_token}
                        }
                    }
        r = requests.post(url=post_url, json=activation_payload)
        if r.status_code != 201:
            logger.error(f'commissioning failed: {r.status_code} from license activation')
            return False

    if 'authSetup' in steps:
        logger.info('perform commissioning.. authentication setup')
        auth_user = 'admin'
        auth_salt = os.popen('date +%s | sha256sum | head -c 8').read().strip()
        pwhash = os.popen(f'printf %s {auth_password}{auth_salt} | sha256sum - | cut -c -64').read().strip()
        auth_payload = {
                'id': 'authentication',
                'step': 'authSetup',
                'data': {
                    'username': auth_user,
                    'password': f'[{auth_salt}]{pwhash}'
                    }
                }

        r = requests.post(url=post_url, json=auth_payload)
        if r.status_code != 201:
            logger.error(f'commissioning failed: {r.status_code} from authentication')
            return False

    if 'connections' in steps:
        logger.info('perform commissioning.. connection setup')
        http_port = 8088
        https_port = 8043
        gan_port = 8060
        use_ssl = False
        port_payload = {
                'id': 'connections',
                'step': 'connections',
                'data': {
                    'http': http_port,
                    'https': https_port,
                    'gan': gan_port,
                    'useSSL': use_ssl
                    }
                }

        r = requests.post(url=post_url, json=port_payload)
        if r.status_code != 201:
            logger.error(f'commissioning failed: {r.status_code} from connections')
            return False

    finalize_payload = {
            'id': 'finished',
            'data': {
                'startGateway': True
                }
            }

    logger.info('perform commissioning.. finalizing')
    r = requests.post(url=post_url, json=finalize_payload)
    if r.status_code != 200:
        logger.error(f'commissioning failed: {r.status_code} from connections')
        return False

    return True

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

    except Exception as e:
        logger.error(f'Failed to install modules {db_path}: {e}')
        return False
    finally:
        if db_conn:
            db_conn.commit()
            db_conn.close()

    return True

def perform_provisioning(deployment: DeploymentType) -> bool:
    status = read_provisioning_status()
    if status != ProvisionStatus.PROVISIONED:
        logger.info('Start provisioning')
        db_path = f'{IGNITION_INSTALL_LOCATION}/data/db/config.idb'
        gwcmd_path = f'{IGNITION_INSTALL_LOCATION}/gwcmd.sh'
        if not register_modules(db_path):
            return False

        # Restart ignition gateway to apply changes
        store_provision_cache(deployment, ProvisionStatus.PROVISIONED)
        os.system(f'{gwcmd_path} -r')
        logger.info('Finish provisioning')
        return True


command = build_ignition_cmd()
ignition_version = os.popen(f'cat "{IGNITION_INSTALL_LOCATION}/lib/install-info.txt" | grep gateway.version | cut -d = -f 2').read().strip()

logger.info(f'Ignition version: {ignition_version}')

# TODO: modify databases

prepare_data_volume()

proc = subprocess.Popen(command.split(' '))

def main_thread_fn(proc: subprocess.Popen):
    proc.wait()

def commisioning_thread_fn():
    if health_check(desc='commisioning', target= ('RUNNING', 'COMMISSIONING'), giveup = ('RUNNING', None)):
        perform_commissioning(auth_password='password', license_key=None)

def provisioning_thread_fn():
    if health_check(desc='provisioning', target= ('RUNNING', None), giveup = None):
        res = perform_provisioning(deployment=DeploymentType.DEV)
        logger.info(f'Provisioning completed res: {res}')

main_thread = Thread(target= main_thread_fn, args = (proc, ))
main_thread.start()

commisioning_thread = Thread(target= commisioning_thread_fn)
commisioning_thread.start()

provisioning_thread = Thread(target= provisioning_thread_fn)
provisioning_thread.start()

time.sleep(10)
logger.info('KILL server after 10 seconds')
# proc.kill()

