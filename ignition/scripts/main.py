#!/bin/bash python3

import os, time, shutil, enum
import json
import subprocess
import requests

from threading import Thread
from typing import Optional, Tuple

from common import logger
from common import IGNITION_VERSION, IGNITION_INSTALL_LOCATION, IGNITION_PORT
from common import DATA_VOLUME_PATH
import provision

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
    # get_url = f'{base_url}/get-step'
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

logger.info(f'Ignition version: {IGNITION_VERSION}')

# TODO: modify databases

prepare_data_volume()

def commisioning_thread_fn():
    if health_check(desc='commisioning', target= ('RUNNING', 'COMMISSIONING'), giveup = ('RUNNING', None)):
        perform_commissioning(auth_password='password', license_key=None)

def provisioning_thread_fn():
    if health_check(desc='provisioning', target= ('RUNNING', None), giveup = None):
        res = provision.perform_provisioning(deployment=provision.DeploymentType.DEV)
        logger.info(f'Provisioning completed res: {res}')

commisioning_thread = Thread(target= commisioning_thread_fn)
commisioning_thread.start()

provisioning_thread = Thread(target= provisioning_thread_fn)
provisioning_thread.start()

command = build_ignition_cmd()
proc = subprocess.Popen(command.split(' '))
proc.wait()

logger.warn(f'end of program')
