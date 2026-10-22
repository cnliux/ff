import os
import re
import hashlib
import requests
from importlib.machinery import SourceFileLoader
import json


def spider(cache, api):
    url, hash_type, hash_val = parse_hash(api)
    name = os.path.basename(url)
    path = cache + '/' + name
    if hash_type:
        download_verified(path, url, hash_type, hash_val)
    elif url.startswith('http'):
        raise RuntimeError('remote .py without ;sha256; is rejected: ' + url)
    else:
        writeFile(path, str.encode(api))
    name = name.split('.')[0]
    return SourceFileLoader(name, path).load_module().Spider()


def download(path, api):
    url, hash_type, hash_val = parse_hash(api)
    if hash_type:
        download_verified(path, url, hash_type, hash_val)
    elif url.startswith('http'):
        raise RuntimeError('remote .py without ;sha256; is rejected: ' + url)
    else:
        writeFile(path, str.encode(api))


def download_verified(path, url, hash_type, hash_val):
    content = redirect(url).content
    if hash_type and not verify_hash(content, hash_type, hash_val):
        raise RuntimeError('hash mismatch for ' + url + ' expected ' + hash_type + ':' + hash_val)
    writeFile(path, content)


def verify_hash(content, typ, expected):
    if typ == 'sha256':
        actual = hashlib.sha256(content).hexdigest()
        return actual == expected
    if typ == 'md5':
        actual = hashlib.md5(content).hexdigest()
        return actual == expected
    return False


def parse_hash(api):
    m = re.search(r';(sha256|md5);([0-9a-fA-F]+)$', api)
    if m:
        return api[:m.start()], m.group(1), m.group(2).lower()
    return api, None, None


def writeFile(path, content):
    with open(path, 'wb') as f:
        f.write(content)


def redirect(url, hops=0):
    if hops > 5:
        raise RuntimeError('too many redirects for ' + url)
    verify = not url.startswith('http://')
    rsp = requests.get(url, allow_redirects=False, verify=verify)
    if 'Location' in rsp.headers:
        return redirect(rsp.headers['Location'], hops + 1)
    else:
        return rsp


def str2json(content):
    return json.loads(content)


def getDependence(ru):
    result = ru.getDependence()
    return result


def getName(ru):
    result = ru.getName()
    return result


def init(ru, extend):
    ru.init(extend)


def homeContent(ru, filter):
    result = ru.homeContent(filter)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def homeVideoContent(ru):
    result = ru.homeVideoContent()
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def categoryContent(ru, tid, pg, filter, extend):
    result = ru.categoryContent(tid, pg, filter, str2json(extend))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def detailContent(ru, array):
    result = ru.detailContent(str2json(array))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def searchContent(ru, key, quick, pg="1"):
    result = ru.searchContent(key, quick, pg)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def playerContent(ru, flag, id, vipFlags):
    result = ru.playerContent(flag, id, str2json(vipFlags))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def liveContent(ru, url):
    result = ru.liveContent(url)
    return result


def localProxy(ru, param):
    result = ru.localProxy(str2json(param))
    return result


def action(ru, action):
    result = ru.action(action)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def destroy(ru):
    ru.destroy()


def run():
    pass


if __name__ == '__main__':
    run()
