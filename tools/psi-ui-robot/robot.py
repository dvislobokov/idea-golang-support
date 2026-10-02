"""Drives the sandbox IDE started with `./gradlew :plugin:runIdeForUiTests` through the Remote Robot server (http://127.0.0.1:8084).

Origin: copied from the user's plugin idea-golang-support (tools/ui-robot/robot.py) and adapted for go-psi: no gopls, run/debug
configurations or settings of that plugin; scripts with __PLACEHOLDERS__ are filled in by `script`; the sandbox check looks for the
go-psi sandbox.

The port is 8084 (ROBOT_PORT overrides it; start the IDE with the same `-ProbotPort=`): 8082 and 8083 belong to other sandboxes on
this machine, and a robot pointed at the IDE of another agent clicks, types and exits there.

    python robot.py wait                         wait until the server answers
    python robot.py windows                      frames and dialogs of the IDE
    python robot.py shot OUT.png [XPATH]         a picture of one component, by default the main frame (or the welcome screen)
    python robot.py find XPATH                   components matching the XPath, with their texts
    python robot.py click XPATH                  click the centre of the first match
    python robot.py clicktext XPATH TEXT         click a text inside the first match (a row of a tree or a list, a tab); add `double` for a double click
    python robot.py open PROJECT_DIR             open a project in the sandbox
    python robot.py action ACTION_ID             invoke an action of the IDE (ReformatCode, GotoDeclaration, Exit, ...)
    python robot.py openfile FILE [LINE]         open a file in the editor, the caret on LINE (1-based)
    python robot.py js FILE.js [--edt]           run JavaScript inside the IDE, print what it returns
    python robot.py script NAME [KEY=VALUE ...]  run scripts/NAME.js with prelude.js first and each __KEY__ replaced by VALUE
    python robot.py tree OUT.html                the component tree with XPaths (what http://127.0.0.1:8084 shows)

Pictures are always of a component of the IDE, painted by the component itself: the `/screenshot` of the server captures the
whole desktop with whatever else is on it, and is deliberately not used.

Pitfalls of Rhino (seen live in idea-golang-support): `const` inside a loop keeps its first value, use `var`; classes of the plugin
live in its own class loader, so load them with `cls("io.github...")` from prelude.js.
"""
import base64
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.request

PORT = os.environ.get("ROBOT_PORT", "8084")
if PORT in ("8082", "8083"):
    raise SystemExit("robot: ports 8082 and 8083 belong to other sandboxes of this machine; use 8084 or another free port")
BASE = "http://127.0.0.1:" + PORT
HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.join(HERE, "scripts")
# HTTP_PROXY of the shell would route 127.0.0.1 through the proxy, which answers 403: never a proxy for the robot
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))
MAIN_WINDOWS = ["//div[@class='IdeFrameImpl']", "//div[@class='FlatWelcomeFrame']"]
DIALOGS = "//div[@class='MyDialog']"


class RobotError(Exception):
    pass


def request(path, body=None, timeout=60):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, headers={"Content-Type": "application/json"} if data else {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.read()
    except urllib.error.HTTPError as e:
        # the server answers 500 with the JSON of the failure (a script error, a timeout): keep its message
        body = e.read().decode("utf-8", errors="replace")
        raise RobotError("robot: HTTP %d for %s: %s" % (e.code, path, body[:3000]))


def call(path, body=None, timeout=60):
    result = json.loads(request(path, body, timeout))
    if result.get("status") != "SUCCESS":
        raise RobotError("robot: %s\n%s" % (result.get("message") or result.get("status"), result.get("log", "")))
    return result


def components(xpath):
    return call("/xpath/components", {"xpath": xpath}).get("elementList") or []


def first(xpath):
    found = components(xpath)
    if not found:
        raise RobotError("robot: nothing matches %s" % xpath)
    return found[0]


def main_window():
    for xpath in MAIN_WINDOWS:
        found = components(xpath)
        if found:
            return found[0]
    raise RobotError("robot: the IDE has no window yet")


def image_bytes(result):
    raw = result["bytes"]
    return base64.b64decode(raw) if isinstance(raw, str) else bytes((value + 256) % 256 for value in raw)


def script_body(script, edt=False):
    return {"script": script, "runInEdt": edt}


def decode_serialized(data):
    """The value of /js/retrieveAny is a Java-serialized object: a String (TC_STRING / TC_LONGSTRING) is decoded exactly."""
    if data[:4] == b"\xac\xed\x00\x05":
        if data[4:5] == b"t":
            length = struct.unpack(">H", data[5:7])[0]
            return data[7:7 + length].decode("utf-8", errors="replace")
        if data[4:5] == b"|":
            length = struct.unpack(">Q", data[5:13])[0]
            return data[13:13 + length].decode("utf-8", errors="replace")
    return data.decode("utf-8", errors="replace")


def js(script, edt=False, timeout=180):
    result = call("/js/retrieveAny", script_body(script, edt), timeout)
    if result.get("bytes") is None:
        return None
    return decode_serialized(image_bytes(result))


def js_string(value):
    return json.dumps(value)


def fill(name, values):
    """scripts/NAME.js with prelude.js in front and every __KEY__ replaced."""
    with open(os.path.join(SCRIPTS, "prelude.js"), encoding="utf-8") as f:
        prelude = f.read()
    with open(os.path.join(SCRIPTS, name if name.endswith(".js") else name + ".js"), encoding="utf-8") as f:
        body = f.read()
    for key, value in values.items():
        body = body.replace("__%s__" % key, str(value))
    return prelude + "\n" + body


def script(name, timeout=180, **values):
    return js(fill(name, values), timeout=timeout)


def shot(path, xpath=None):
    component = first(xpath) if xpath else main_window()
    result = call("/%s/screenshot?isPaintingMode=true" % component["id"], timeout=60)
    with open(path, "wb") as out:
        out.write(image_bytes(result))
    return path


def wait_up(tries=300):
    for _ in range(tries):
        try:
            request("/", timeout=3)
            check_sandbox()
            return True
        except (urllib.error.URLError, OSError, RobotError):
            time.sleep(2)
    return False


def command_wait(_):
    if not wait_up():
        raise SystemExit("robot: no answer from %s" % BASE)
    print("robot is up")


def command_windows(_):
    for xpath in MAIN_WINDOWS + [DIALOGS]:
        for component in components(xpath):
            print(xpath, component["className"].split(".")[-1], "%sx%s" % (component["width"], component["height"]), component["id"])


def command_shot(args):
    print(shot(args[0], args[1] if len(args) > 1 else None))


def command_find(args):
    for component in components(args[0]):
        texts = call("/%s/data" % component["id"], {}).get("componentData", {}).get("textDataList", [])  # POST: a GET is answered 404
        print(component["className"].split(".")[-1], "%sx%s" % (component["width"], component["height"]), component["id"], "|", " ".join(t["text"] for t in texts)[:200])


def command_click(args):
    component = first(args[0])
    call("/%s/js/execute" % component["id"], script_body("robot.click(component)"))  # without /js it is the route for serialized lambdas
    print("clicked", component["className"].split(".")[-1])


def command_clicktext(args):
    component = first(args[0])
    texts = call("/%s/data" % component["id"], {}).get("componentData", {}).get("textDataList", [])
    match = next((t for t in texts if t["text"] == args[1]), None) or next((t for t in texts if args[1] in t["text"]), None)
    if match is None:
        raise SystemExit("robot: no text %r in %s; there is: %s" % (args[1], args[0], " | ".join(t["text"] for t in texts)[:400]))
    point = "new java.awt.Point(%d, %d)" % (match["point"]["x"], match["point"]["y"])
    click = "robot.doubleClick(component, %s)" % point if "double" in args[2:] else "robot.click(component, %s)" % point
    call("/%s/js/execute" % component["id"], script_body(click))
    print("clicked", repr(match["text"]))


def open_project(path):
    code = """
        importClass(com.intellij.ide.impl.ProjectUtil)
        importClass(com.intellij.openapi.application.ApplicationManager)
        const path = %s
        ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { ProjectUtil.openOrImport(path, null, true) } }))
    """ % js_string(path.replace("\\", "/"))
    call("/js/execute", script_body(code))


def command_open(args):
    open_project(args[0])
    print("opening", args[0])


def action(action_id):
    code = """
        importClass(com.intellij.openapi.actionSystem.ActionManager)
        importClass(com.intellij.openapi.actionSystem.ActionPlaces)
        importClass(com.intellij.openapi.application.ApplicationManager)
        importClass(com.intellij.openapi.wm.IdeFocusManager)
        const id = %s
        ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
            const action = ActionManager.getInstance().getAction(id)
            if (action == null) throw new java.lang.IllegalArgumentException("No action " + id)
            const focus = IdeFocusManager.getGlobalInstance().getFocusOwner()
            ActionManager.getInstance().tryToExecute(action, null, focus, ActionPlaces.UNKNOWN, true)
        } }))
    """ % js_string(action_id)
    call("/js/execute", script_body(code))


def command_action(args):
    action(args[0])
    print("invoked", args[0])


IN_PROJECT = """
    importClass(com.intellij.openapi.project.ProjectManager)
    importClass(com.intellij.openapi.application.ApplicationManager)
    importClass(com.intellij.openapi.vfs.LocalFileSystem)
    const projects = ProjectManager.getInstance().getOpenProjects()
    if (projects.length == 0) throw new java.lang.IllegalStateException("No project is open")
    const project = projects[projects.length - 1]
    function later(body) { ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: body })) }
    function file(path) {
        const found = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
        if (found == null) throw new java.lang.IllegalArgumentException("No file " + path)
        return found
    }
"""


def open_file(path, line=1):
    code = IN_PROJECT + """
        importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
        const target = file(%s)
        later(function () { new OpenFileDescriptor(project, target, %d, 0).navigate(true) })
    """ % (js_string(path.replace("\\", "/")), int(line) - 1)
    call("/js/execute", script_body(code))


def command_openfile(args):
    open_file(args[0], args[1] if len(args) > 1 else 1)
    print("opened", args[0])


def command_js(args):
    with open(args[0], encoding="utf-8") as f:
        source = f.read()
    print(js(source, edt="--edt" in args))


def command_script(args):
    values = dict(arg.split("=", 1) for arg in args[1:])
    print(script(args[0], **values))


def command_tree(args):
    with open(args[0], "wb") as out:
        out.write(request("/hierarchy", timeout=120))
    print(args[0])


COMMANDS = {
    "wait": command_wait, "windows": command_windows, "shot": command_shot, "find": command_find, "click": command_click,
    "open": command_open, "action": command_action, "js": command_js, "script": command_script, "tree": command_tree,
    "clicktext": command_clicktext, "openfile": command_openfile,
}


def check_sandbox():
    """Refuses to touch an IDE that is not a go-psi sandbox: another agent may have one on the same port."""
    path = (js("com.intellij.openapi.application.PathManager.getConfigPath()") or "").replace("\\", "/")
    if "go-psi" not in path or "/sandbox/" not in path or "_runIdeForUiTests" not in path:
        raise RobotError("robot: the IDE on %s is not a go-psi sandbox (its configuration is in %r): wrong port?" % (BASE, path))


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        raise SystemExit(__doc__)
    try:
        # `wait` is what finds out whether anything listens at all; everything else acts, and acts on the right IDE only
        if sys.argv[1] != "wait":
            check_sandbox()
        COMMANDS[sys.argv[1]](sys.argv[2:])
    except RobotError as e:
        raise SystemExit(str(e))
