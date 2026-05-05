/**
 *  Hubitat - Controlart Ethernet Relay Module (10 relés / 12 entradas)
 *  
 *  
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *  

 *        1.0 22/5/2024  - V.BETA 1 
 *        1.1 22/5/2024  - Added check connection time every 5min. 
 *        1.2 24/5/2024  - Fixed bugs. Added Relay numbers. Changed Initialize, Configure, connection.
 *        1.3 27/11/2024  - Fixed bugs.
 *  	  1.4 11/8/2025  - Keep output-suppression window after pulses, On pulse mode, also set contact to 'open' on falling edge (1->0) as a fallback, releaseInput() signature simplified to 'def releaseInput(data)' (compat Hubitat scheduler)
 * 		  				- Read (PUSH button) event in board to create events from inputs. 
 */

import groovy.transform.Field

metadata {
  definition (name: "Controlart - Ethernet Relay Module", namespace: "TRATO", author: "VH") {
    capability "Configuration"
    capability "Initialize"
    capability "Refresh"
    capability "Switch"
    capability "PushableButton"

    command "keepalive"
    command "getfw"
    command "getmac"
    command "getstatus"
    command "reconnect"
    command "cleanup"

    command "allOn"
    command "allOff"
    command "masterOn"
    command "masterOff"

    attribute "boardstatus", "string"
    attribute "firmware", "string"
    attribute "mac3_5", "string"
  }

  preferences {
    input "device_IP_address", "text", title: "IP Address of Board", required: true
    input "device_port", "number", title: "IP Port of Board", required: true, defaultValue: 4998

    input "outputs", "number", title: "How many Relays (max 10)", defaultValue: 10
    input "inputs", "number", title: "How many Inputs (max 12)", defaultValue: 12

    input "autoCreateChildren", "bool", title: "Auto-create child devices (Switch/Contact)", defaultValue: true
    input "inputsPulseMode", "bool", title: "Inputs como Pulso (momentary)?", defaultValue: true
    input "inputsAsButtons", "bool", title: "Emitir eventos de Botão (PushableButton) no Parent?", defaultValue: true
    input "inputsCreateContactChildren", "bool", title: "Criar também childs Contact para inputs?", defaultValue: false

    input "pulseReleaseMs", "number", title: "Tempo de retorno p/ 'open' (ms)", defaultValue: 150
    input "pulseDebounceMs", "number", title: "Debounce do pulso (ms)", defaultValue: 50
    input "suppressOutputsMs", "number", title: "Ignorar atualização de OUT após pulso (ms)", defaultValue: 250

    input 'logInfo',  'bool', title: 'Show Info Logs?',  defaultValue: true
    input 'logWarn',  'bool', title: 'Show Warning Logs?', defaultValue: true
    input 'logDebug', 'bool', title: 'Show Debug Logs?', description: 'Only leave on when required', defaultValue: true
    input 'logTrace', 'bool', title: 'Show Detailed Logs?', description: 'Only leave on when required', defaultValue: false
  }
}

// ======== Constantes / Estado ========

@Field static Integer checkInterval = 600
@Field static String drvThis = "Controlart-Driver"

@Field static String CRLF = "\r\n"
@Field static String lineBuffer = ""
@Field static String OUT_PREFIX = "Switch-"
@Field static String IN_PREFIX  = "Input-"

// ======== Helpers MAC ========

private List<String> extractLast3HexBytes(String tail) {
  if (!tail) return []
  String cleaned = tail.replaceAll("[\\r\\n\\s]","")
  cleaned = cleaned.replaceFirst("^[Mm][Aa][Cc][Aa][Dd][Dd][Rr][_ ,]*", "")
  if (cleaned.startsWith(",")) cleaned = cleaned.substring(1)
  cleaned = cleaned.replaceAll("0[xX]", "")
  String[] parts = cleaned.split("[\\-,]")
  def hex2 = parts.findAll { it != null && it.length()==2 && it ==~ /^[0-9A-Fa-f]{2}$/ }
  if (hex2.size() >= 3) return hex2[-3..-1].collect { it.toUpperCase() }
  return hex2.collect { it.toUpperCase() }
}

private String format0x(List<String> bytes) {
  if (!bytes || bytes.isEmpty()) return ""
  return bytes.collect { "0x${it}" }.join(",")
}

private String ensureMac() {
  String raw = state.macaddress ?: ""
  List<String> last3 = extractLast3HexBytes(raw)
  String norm = format0x(last3)
  if (norm && norm != raw) {
    state.macaddress = norm
    if ((Boolean)settings.logDebug) log.debug "${drvThis}: MAC normalizado -> ${norm} (de '${raw}')"
  }
  return norm
}

// ======== Lifecycle ========

def installed() {
  state.childscreated = 0
  state.outputcount = (settings.outputs ?: 10) as int
  state.inputcount  = (settings.inputs  ?: 12) as int
  state.prevInputs = (0..<12).collect{ "0" }
  state.lastPulseTs = (0..<12).collect{ 0L }
  state.suppressOutUntil = 0L
  sendEvent(name:"numberOfButtons", value: state.inputcount)
  runIn(1800, logsOff)
}

def uninstalled() {
  unschedule()
  try { interfaces.rawSocket.close() } catch (e) {}
}

def updated() { initialize() }
def configure() { initialize() }

def initialize() {
  unschedule()
  try { interfaces.rawSocket.close() } catch (e) {}

  state.lastMessageReceived = ""
  state.lastMessageReceivedAt = 0L
  if (!state.prevInputs || !(state.prevInputs instanceof List)) state.prevInputs = (0..<12).collect{ "0" }
  if (!state.lastPulseTs || !(state.lastPulseTs instanceof List)) state.lastPulseTs = (0..<12).collect{ 0L }
  state.suppressOutUntil = 0L
  state.inputsStr = ""
  state.outputsStr = ""
  state.primeira = ""
  state.lastprimeira = ""
  lineBuffer = ""

  state.outputcount = Math.min((settings.outputs ?: 10) as int, 10)
  state.inputcount  = Math.min((settings.inputs  ?: 12) as int, 12)
  sendEvent(name:"numberOfButtons", value: state.inputcount)

  if (!device_IP_address) { logError 'IP do Device não configurado.'; return }
  if (!device_port) { logError 'Porta do Device não configurada.'; return }

  try {
    logInfo("Conectando em ${device_IP_address}:${(int)device_port} ...")
    interfaces.rawSocket.connect(device_IP_address, (int) device_port)
    state.lastMessageReceivedAt = now()
    runIn(checkInterval, "connectionCheck")
  } catch (e) {
    logError("initialize connect error: ${e.message}")
    sendEvent(name: "boardstatus", value: "offline", isStateChange: true)
    return
  }

  pauseExecution(150)
  getmac()

  if (settings.autoCreateChildren && (state.childscreated != 1)) {
    createChildren()
  }

  pauseExecution(100)
  getstatus()
}

// ======== Child creation ========

private createChildren() {
  String base = device.id as String

  for (int i = 1; i <= state.outputcount; i++) {
    String dni = "${base}-${OUT_PREFIX}${i}"
    if (!getChildDevice(dni)) {
      addChildDevice("hubitat", "Generic Component Switch", dni,
        [name: "${device.displayName} ${OUT_PREFIX}${i}", isComponent: true])
    }
  }

  if (settings.inputsCreateContactChildren) {
    for (int j = 1; j <= state.inputcount; j++) {
      String dni = "${base}-${IN_PREFIX}${j}"
      if (!getChildDevice(dni)) {
        addChildDevice("hubitat", "Generic Component Contact Sensor", dni,
          [name: "${device.displayName} ${IN_PREFIX}${j}", isComponent: true])
      }
    }
  } else {
    for (int j = 1; j <= 12; j++) {
      String dni = "${base}-${IN_PREFIX}${j}"
      def cd = getChildDevice(dni)
      if (cd) deleteChildDevice(dni)
    }
  }

  state.childscreated = 1
}

// ======== Comandos ========

def keepalive() { reconnect() }

def reconnect() {
  try { interfaces.rawSocket.close() } catch (e) {}
  sendEvent(name: "boardstatus", value: "offline", isStateChange: true)
  initialize()
}

def getmac()         { sendCommand("get_mac_addr") }
def getfw()          { sendCommand("get_firmware_version") }

def getstatus() {
  String mac = ensureMac()
  if (!mac) { getmac(); pauseExecution(150); mac = ensureMac() }
  if (!mac) return
  sendCommand("mdcmd_getmd,${mac}")
}

def on()  { allOn() }
def off() { allOff() }

def allOn()  {
  String mac = ensureMac()
  if (mac) sendCommand("mdcmd_setallonmd,${mac}")
}
def allOff() {
  String mac = ensureMac()
  if (mac) sendCommand("mdcmd_setalloffmd,${mac}")
}

def masterOn()  { sendCommand("mdcmd_setmasteronmd") }
def masterOff() { sendCommand("mdcmd_setmasteroffmd") }

// ======== Envio TCP ========

private sendCommand(String s) {
  String frame = s + CRLF
  logDebug("TX: ${s}")
  interfaces.rawSocket.sendMessage(frame)
}

// ======== Parser TCP ========

private boolean looksLikeHex(String s) {
  if (s == null) return false
  return s ==~ /^[0-9A-Fa-f\s]+$/
}

def parse(String msg) {
  state.lastMessageReceived = new Date(now()).toString()
  state.lastMessageReceivedAt = now()
  if (msg == null) return

  String chunk
  if (looksLikeHex(msg)) {
    try {
      byte[] ba = hubitat.helper.HexUtils.hexStringToByteArray(msg.replaceAll("\\s+",""))
      chunk = new String(ba as byte[])
    } catch (e) {
      logWarn("Falha ao decodificar HEX: ${e.message}")
      return
    }
  } else {
    chunk = msg
  }

  lineBuffer += chunk
  int idx
  while ((idx = lineBuffer.indexOf("\r\n")) >= 0) {
    String line = lineBuffer.substring(0, idx)
    lineBuffer = lineBuffer.substring(idx + 2)
    handleLine(line?.trim())
  }
}

private void handleLine(String line) {
  if (!line) return
  logTrace("RX: ${line}")

  if (line.equalsIgnoreCase("Parse Error!") || line.equalsIgnoreCase("Parse Error")) {
    logWarn("Módulo retornou 'Parse Error!' (provável comando anterior inválido).")
    return
  }

  if (line.startsWith("macaddr")) {
    int cut = Math.max(line.indexOf('_'), line.indexOf(','))
    String tail = (cut >= 0 && cut+1 < line.length()) ? line.substring(cut+1) : line
    List<String> last3 = extractLast3HexBytes(tail)
    String formatted = format0x(last3)
    if (formatted) {
      state.macaddress = formatted
      sendEvent(name: "mac3_5", value: formatted, isStateChange: true)
      sendEvent(name: "boardstatus", value: "online", isStateChange: true)
      logInfo("MAC (v6pb2): ${formatted}")
      return
    } else {
      logWarn("Não foi possível extrair 3 bytes hex do MAC em: '${line}'")
    }
  }

  if (line.startsWith("VERSION")) {
    sendEvent(name: "firmware", value: line, isStateChange: true)
    return
  }

  if (line.startsWith("setcmd,")) {
    def arr = line.split(",")
    if (arr.size() >= 24) {
      updateInputsOutputs(arr)
      sendEvent(name: "boardstatus", value: "online", isStateChange: true)
      return
    }
  }

  if (line.equalsIgnoreCase("MasterOn") || line.equalsIgnoreCase("MasterOff")) {
    runInMillis(150, "getstatus")
    return
  }
}

// ======== Atualização de IN/OUT ========

private void updateInputsOutputs(String[] arr) {
  int inStart = 2
  int inEnd   = inStart + 12 - 1
  int outStart = inEnd + 1
  int outEnd   = outStart + 10 - 1

  String ins  = (inStart..inEnd).collect { arr[it] }.join("")
  String outs = (outStart..outEnd).collect { arr[it] }.join("")
  state.inputsStr  = ins

  long nowTs = now()
  long until = (state.suppressOutUntil ?: 0L) as long
  boolean allowOutUpdate = (nowTs >= until)

  String base = device.id as String
  boolean pulse = (settings.inputsPulseMode == true)
  long debounce = (settings.pulseDebounceMs ?: 50) as long
  long releaseMs = (settings.pulseReleaseMs ?: 150) as long
  long suppressMs = (settings.suppressOutputsMs ?: 250) as long

  // ---- Inputs
  for (int i = 0; i < state.inputcount; i++) {
    int idx = inStart + i
    if (idx >= arr.size()) break
    String val = arr[idx]?.trim()
    if (!val) continue

    String prev = (state.prevInputs[i] ?: "0") as String
    if (!pulse) {
      if (settings.inputsCreateContactChildren) {
        String contact = (val == "1") ? "closed" : "open"
        def cd = getChildDevice("${base}-${IN_PREFIX}${i+1}")
        if (cd) cd.parse([[name:"contact", value: contact]])
      }
    } else {
      // Pulse: borda 0->1 => botão/contato + supressão
      if (prev != "1" && val == "1") {
        long lastTs = (state.lastPulseTs[i] ?: 0L) as long
        if (nowTs - lastTs >= debounce) {
          if (settings.inputsAsButtons) {
            sendEvent(name:"pushed", value: i+1, isStateChange:true, type:"digital")
          }
          if (settings.inputsCreateContactChildren) {
            String dni = "${base}-${IN_PREFIX}${i+1}"
            def cd = getChildDevice(dni)
            if (cd) {
              cd.parse([[name:"contact", value:"closed", isStateChange:true]])
              runInMillis((int)releaseMs, "releaseInput", [data:[dni:dni]])
            }
          }
          state.suppressOutUntil = nowTs + suppressMs
        }
        state.lastPulseTs[i] = nowTs
      }
      // Fallback: borda 1->0 => abre contato se ainda estiver fechado
      if (prev == "1" && val == "0" && settings.inputsCreateContactChildren) {
        String dni = "${base}-${IN_PREFIX}${i+1}"
        def cd = getChildDevice(dni)
        if (cd) cd.parse([[name:"contact", value:"open", isStateChange:true]])
      }
    }
    state.prevInputs[i] = val
  }

  // ---- Outputs
  if (allowOutUpdate) {
    state.outputsStr = outs
    for (int j = 0; j < state.outputcount; j++) {
      int idx = outStart + j
      if (idx >= arr.size()) break
      String val = arr[idx]?.trim()
      if (!val) continue
      String sw = (val == "1") ? "on" : "off"
      def cd = getChildDevice("${base}-${OUT_PREFIX}${j+1}")
      if (cd) cd.parse([[name:"switch", value: sw]])
    }
  } else {
    logTrace("Atualização de OUT ignorada por supressão pós-pulso (até ${new Date(state.suppressOutUntil as long)})")
  }
}

// Retorna o contato para 'open' após o tempo de pulso
def releaseInput(data) {
  try {
    String dni = data?.dni
    if (!dni) return
    def cd = getChildDevice(dni)
    if (cd) cd.parse([[name:"contact", value:"open", isStateChange:true]])
  } catch (e) {
    logWarn("releaseInput error: ${e.message}")
  }
}

// ======== Child callbacks ========

void componentRefresh(cd) { getstatus() }

void componentOn(cd) {
  int ch = childToOutputIndex(cd)
  if (ch < 0) return
  String mac = ensureMac()
  if (!mac) { getmac(); pauseExecution(120); mac = ensureMac() }
  if (!mac) return
  sendCommand("mdcmd_sendrele,${mac},${ch},1")
  getChildDevice(cd.deviceNetworkId).parse([[name:"switch", value:"on"]])
  runInMillis(120, "getstatus")
}

void componentOff(cd) {
  int ch = childToOutputIndex(cd)
  if (ch < 0) return
  String mac = ensureMac()
  if (!mac) { getmac(); pauseExecution(120); mac = ensureMac() }
  if (!mac) return
  sendCommand("mdcmd_sendrele,${mac},${ch},0")
  getChildDevice(cd.deviceNetworkId).parse([[name:"switch", value:"off"]])
  runInMillis(120, "getstatus")
}

private int childToOutputIndex(cd) {
  String dni = cd.deviceNetworkId ?: ""
  int pos = dni.indexOf("-${OUT_PREFIX}")
  if (pos < 0) return -1
  try {
    String num = dni.substring(pos + ("-${OUT_PREFIX}").length())
    int n = num as int
    return Math.max(0, Math.min(9, n-1))
  } catch (e) {
    return -1
  }
}

// ======== Conexão / watchdog ========

def connectionCheck() {
  long n = now()
  if (!state.lastMessageReceivedAt) state.lastMessageReceivedAt = 0L
  if (n - state.lastMessageReceivedAt > (checkInterval * 1000)) {
    logWarn("Sem mensagens há ${(n - state.lastMessageReceivedAt)/60000} min; reconectando...")
    reconnect()
  } else {
    logDebug("Connection Check OK.")
    sendEvent(name: "boardstatus", value: "online")
    runIn(checkInterval, "connectionCheck")
  }
}

def refresh() { getstatus() }

def cleanup() {
  state.clear()
  lineBuffer = ""
  logInfo("State limpo.")
}

// ======== Logs ========

def logsOff() {
  log.warn 'logging disabled...'
  device.updateSetting('logInfo', [value:'false', type:'bool'])
  device.updateSetting('logWarn', [value:'false', type:'bool'])
  device.updateSetting('logDebug', [value:'false', type:'bool'])
  device.updateSetting('logTrace', [value:'false', type:'bool'])
}

void logDebug(String msg) { if ((Boolean)settings.logDebug) log.debug "${drvThis}: ${msg}" }
void logInfo(String msg)  { if ((Boolean)settings.logInfo)  log.info  "${drvThis}: ${msg}" }
void logTrace(String msg) { if ((Boolean)settings.logTrace) log.trace "${drvThis}: ${msg}" }
void logWarn(String msg)  { if ((Boolean)settings.logWarn)  log.warn  "${drvThis}: ${msg}" }
void logError(String msg) { log.error "${drvThis}: ${msg}" }
