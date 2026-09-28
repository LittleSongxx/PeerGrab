#!/usr/bin/env python3
"""Generate dependency-free JMeter 5.6.3 HTTP plans for S1/S3/S4."""
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent


def prop(parent, kind, name, value):
    ET.SubElement(parent, kind + "Prop", name=name).text = str(value)


def pair(tree, tag, test_name, gui, **attributes):
    element = ET.SubElement(tree, tag, {"guiclass": gui, "testclass": tag,
                                        "testname": test_name, "enabled": "true", **attributes})
    return element, ET.SubElement(tree, "hashTree")


def plan(name, sequential=False):
    root = ET.Element("jmeterTestPlan", version="1.2", properties="5.0", jmeter="5.6.3")
    root_tree = ET.SubElement(root, "hashTree")
    test_plan, test_tree = pair(root_tree, "TestPlan", name, "TestPlanGui")
    prop(test_plan, "bool", "TestPlan.functional_mode", "false")
    ET.SubElement(test_plan, "collectionProp", name="TestPlan.thread_groups")
    args = ET.SubElement(test_plan, "elementProp", name="TestPlan.user_defined_variables",
                         elementType="Arguments", guiclass="ArgumentsPanel",
                         testclass="Arguments", testname="User Defined Variables", enabled="true")
    ET.SubElement(args, "collectionProp", name="Arguments.arguments")
    prop(test_plan, "bool", "TestPlan.serialize_threadgroups", str(sequential).lower())
    return root, test_tree


def group(tree, name, threads, forever=False, loops="1"):
    item, children = pair(tree, "ThreadGroup", name, "ThreadGroupGui")
    controller = ET.SubElement(item, "elementProp", name="ThreadGroup.main_controller",
                               elementType="LoopController", guiclass="LoopControlPanel",
                               testclass="LoopController", testname="Loop Controller", enabled="true")
    prop(controller, "bool", "LoopController.continue_forever", "true" if forever else "false")
    prop(controller, "string", "LoopController.loops", "-1" if forever else loops)
    prop(item, "string", "ThreadGroup.num_threads", threads)
    prop(item, "string", "ThreadGroup.ramp_time", "0")
    prop(item, "bool", "ThreadGroup.scheduler", "false")
    prop(item, "string", "ThreadGroup.on_sample_error", "stopthread")
    return children


def csv(tree, file_prop, columns):
    item, _ = pair(tree, "CSVDataSet", "Private fixture CSV", "TestBeanGUI")
    prop(item, "string", "filename", "${__P(" + file_prop + ")}")
    prop(item, "string", "fileEncoding", "UTF-8")
    prop(item, "string", "variableNames", columns)
    prop(item, "bool", "ignoreFirstLine", "true")
    prop(item, "string", "delimiter", ",")
    prop(item, "bool", "quotedData", "false")
    prop(item, "bool", "recycle", "false")
    prop(item, "bool", "stopThread", "true")
    prop(item, "string", "shareMode", "shareMode.all")


def sync(tree, size):
    item, _ = pair(tree, "SyncTimer", "Release ready clients together (bounded wait)", "TestBeanGUI")
    prop(item, "string", "groupSize", size)
    prop(item, "string", "timeoutInMs", "60000")


def headers(tree, request_id=None):
    manager, _ = pair(tree, "HeaderManager", "JWT and JSON", "HeaderPanel")
    fields = ET.SubElement(manager, "collectionProp", name="HeaderManager.headers")
    values = [("Authorization", "Bearer ${token}"), ("Content-Type", "application/json")]
    if request_id is not None:
        values.append(("X-Request-Id", request_id))
    for name, value in values:
        header = ET.SubElement(fields, "elementProp", name="", elementType="Header")
        prop(header, "string", "Header.name", name)
        prop(header, "string", "Header.value", value)


def request(tree, label, method, path, body=None, classification=None):
    item, children = pair(tree, "HTTPSamplerProxy", label, "HttpTestSampleGui")
    args = ET.SubElement(item, "elementProp", name="HTTPsampler.Arguments", elementType="Arguments",
                         guiclass="HTTPArgumentsPanel", testclass="Arguments",
                         testname="User Defined Variables", enabled="true")
    entries = ET.SubElement(args, "collectionProp", name="Arguments.arguments")
    if body is not None:
        argument = ET.SubElement(entries, "elementProp", name="", elementType="HTTPArgument")
        prop(argument, "bool", "HTTPArgument.always_encode", "false")
        prop(argument, "string", "Argument.value", body)
        prop(argument, "string", "Argument.metadata", "=")
        prop(argument, "bool", "HTTPArgument.use_equals", "false")
        prop(argument, "string", "Argument.name", "")
    prop(item, "string", "HTTPSampler.domain", "${__P(host,127.0.0.1)}")
    prop(item, "string", "HTTPSampler.port", "${__P(port,8080)}")
    prop(item, "string", "HTTPSampler.protocol", "${__P(protocol,http)}")
    prop(item, "string", "HTTPSampler.contentEncoding", "UTF-8")
    prop(item, "string", "HTTPSampler.path", path)
    prop(item, "string", "HTTPSampler.method", method)
    prop(item, "string", "HTTPSampler.connect_timeout", "${__P(connect_timeout_ms,5000)}")
    prop(item, "string", "HTTPSampler.response_timeout", "${__P(response_timeout_ms,30000)}")
    prop(item, "bool", "HTTPSampler.follow_redirects", "false")
    prop(item, "bool", "HTTPSampler.auto_redirects", "false")
    prop(item, "bool", "HTTPSampler.use_keepalive", "true")
    prop(item, "bool", "HTTPSampler.DO_MULTIPART_POST", "false")
    if body is not None:
        prop(item, "bool", "HTTPSampler.postBodyRaw", "true")
    if classification:
        script, _ = pair(children, "JSR223PostProcessor", "Validate HTTP and business JSON",
                         "TestBeanGUI")
        prop(script, "string", "scriptLanguage", "groovy")
        prop(script, "string", "cacheKey", "")
        prop(script, "string", "script", classification)
    return children


BASE_CHECK = """def status = prev.getResponseCode()
vars.put('peergrabOutcome', 'UNCLASSIFIED')
def body
try { body = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString()) }
catch (Exception ignored) { prev.setSuccessful(false); vars.put('peergrabOutcome', 'INVALID_JSON'); return }
if (!(body instanceof Map)) {
  prev.setSuccessful(false); vars.put('peergrabOutcome', 'HTTP_OR_SHAPE_ERROR'); return
}
def code = body.code?.toString()
"""

STRICT_CHECK = BASE_CHECK + """if (status != '200') {
  prev.setSuccessful(false); vars.put('peergrabOutcome', 'HTTP_OR_SHAPE_ERROR'); return
}
"""

S1_CHECK = STRICT_CHECK + """def expectedReject = code in ['SLOT_FULL','GRAB_CONFLICT','GRAB_RATE_LIMITED']
def success = code == 'OK' && body.data?.grabbed == true
prev.setSuccessful(success || expectedReject)
vars.put('peergrabOutcome', success ? 'GRABBED' : (expectedReject ? code : 'UNEXPECTED'))
"""
S3_READ_CHECK = STRICT_CHECK + """def ok = code == 'OK' && body.data?.id?.toString() == vars.get('errand_id')
prev.setSuccessful(ok)
vars.put('peergrabOutcome', ok ? 'DETAIL_OK' : 'DETAIL_MISMATCH')
"""
S3_PUBLISH_CHECK = STRICT_CHECK + """def id = body.data?.errandId?.toString()
def ok = code == 'OK' && id ==~ /[1-9][0-9]*/
prev.setSuccessful(ok)
vars.put('peergrabOutcome', ok ? 'PUBLISHED' : 'PUBLISH_MISMATCH')
if (ok) vars.put('errand_id', id)
"""
S3_MIXED_READ_CHECK = STRICT_CHECK + """def ok = code == 'OK' && body.data?.id?.toString() == vars.get('errand_id') && body.data?.status == 'PUBLISHED'
prev.setSuccessful(ok)
vars.put('peergrabOutcome', ok ? 'READ_AFTER_WRITE_OK' : 'READ_AFTER_WRITE_MISMATCH')
"""
S4_DISTINCT_CHECK = STRICT_CHECK + """def ok = code == 'OK' && body.data?.result == 'SETTLED'
prev.setSuccessful(ok)
vars.put('peergrabOutcome', ok ? 'SETTLED' : 'UNEXPECTED')
"""
S4_SAME_CHECK = BASE_CHECK + """if (!(status ==~ /[0-9]{3}/) || Integer.parseInt(status) < 200 || Integer.parseInt(status) >= 500) {
  prev.setSuccessful(false); vars.put('peergrabOutcome', 'HTTP_OR_SHAPE_ERROR'); return
}
def result = body.data?.result?.toString()
def settled = status == '200' && code == 'OK' && result == 'SETTLED'
def duplicate = result in ['ALREADY_SETTLED','CONFLICT'] || code == 'SETTLE_CONFLICT'
prev.setSuccessful(settled || duplicate)
vars.put('peergrabOutcome', settled ? 'SETTLED' : (duplicate ? 'DUPLICATE' : 'UNEXPECTED'))
"""


def s1():
    root, tree = plan("PeerGrab S1 one-slot spike")
    t = group(tree, "S1 one task", "${__P(users,16)}")
    csv(t, "s1_csv", "token,request_id")
    headers(t, "${request_id}")
    sync(t, "${__P(users,16)}")
    request(t, "S1 grab", "POST", "/api/errands/${__P(errand_id)}/grab", "{}", S1_CHECK)
    return root


def s3_read():
    root, tree = plan("PeerGrab S3 cache read (off/on/hot)")
    t = group(tree, "S3 fixed-count closed-loop detail", "${__P(threads,50)}",
              loops="${__P(iterations,100)}")
    csv(t, "s3_read_csv", "errand_id,token")
    headers(t)
    request(t, "S3 detail", "GET", "/api/errands/${errand_id}", classification=S3_READ_CHECK)
    return root


def s3_mixed():
    root, tree = plan("PeerGrab S3 publish and read nine times")
    t = group(tree, "S3 mixed 1 write + 9 reads", "${__P(threads,50)}")
    csv(t, "s3_mixed_csv", "token,request_id")
    # JMeter 5.6.3 does not reinitialize a finished nested LoopController
    # for every parent cycle in this plan. Keep one explicit outer cycle and
    # nine consecutive samplers so every publish is followed by nine reads.
    headers(t)
    cycle, cycle_tree = pair(t, "LoopController", "Repeated publish/read cycles", "LoopControlPanel")
    prop(cycle, "bool", "LoopController.continue_forever", "false")
    prop(cycle, "string", "LoopController.loops", "${__P(iterations,10)}")
    request(cycle_tree, "S3 publish", "POST", "/api/errands",
            '{"title":"jmeter_${__P(run_id)}_${__threadNum}_${__counter(TRUE)}","rewardCents":100,"slotTotal":1}',
            S3_PUBLISH_CHECK)
    for _ in range(9):
        request(cycle_tree, "S3 read after write", "GET", "/api/errands/${errand_id}",
                classification=S3_MIXED_READ_CHECK)
    return root


def s4():
    root, tree = plan("PeerGrab S4 distinct and same-task settlement", sequential=True)
    distinct = group(tree, "S4 distinct delivered tasks", "${__P(threads,32)}", forever=True)
    csv(distinct, "s4_distinct_csv", "errand_id,token")
    headers(distinct)
    request(distinct, "S4 distinct settle", "POST", "/api/errands/${errand_id}/settle", "{}",
            S4_DISTINCT_CHECK)
    same = group(tree, "S4 same task duplicate attempts", "${__P(same_attempts,8)}")
    csv(same, "s4_same_csv", "errand_id,token")
    headers(same)
    sync(same, "${__P(same_attempts,8)}")
    request(same, "S4 same-task settle", "POST", "/api/errands/${errand_id}/settle", "{}",
            S4_SAME_CHECK)
    return root


def main():
    plans = {"s1.jmx": s1(), "s3_read.jmx": s3_read(),
             "s3_mixed.jmx": s3_mixed(), "s4.jmx": s4()}
    for filename, root in plans.items():
        ET.indent(root, space="  ")
        ET.ElementTree(root).write(HERE / filename, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    main()
