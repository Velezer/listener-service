use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::{JavaVM, JNIEnv};
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::runtime::Builder;
use tokio_tungstenite::connect_async;
use futures_util::StreamExt;

static STOP: AtomicBool = AtomicBool::new(false);

const BUFFER_SIZE: usize = 128;

/// Call a static void method on WsService with a single String argument.
fn call_kotlin(vm: &JavaVM, ws_service_class: &GlobalRef, method: &str, msg: &str) {
    let mut env = match vm.attach_current_thread() {
        Ok(env) => env,
        Err(_) => return,
    };

    let jmsg = match env.new_string(msg) {
        Ok(s) => s,
        Err(_) => return,
    };

    let ws_service_class_ref: &JClass = ws_service_class.as_obj().into();

    let _ = env.call_static_method(
        ws_service_class_ref,
        method,
        "(Ljava/lang/String;)V",
        &[JValue::Object(&JObject::from(jmsg))],
    );
}

#[no_mangle]
pub extern "system" fn Java_com_listener_WsService_startWs(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) {
    let url: String = env.get_string(&url).unwrap().into();
    let vm = env.get_java_vm().unwrap();
    let ws_service_class = env.new_global_ref(_class).unwrap();

    STOP.store(false, Ordering::Relaxed);
    call_kotlin(&vm, &ws_service_class, "onWsStarted", "WebSocket listener started");

    let rt = Builder::new_current_thread().enable_all().build().unwrap();

    rt.block_on(async move {
        let mut buffer = [0u8; BUFFER_SIZE];
        let mut attempt: u32 = 0;

        while !STOP.load(Ordering::Relaxed) {
            attempt += 1;
            call_kotlin(
                &vm,
                &ws_service_class,
                "onWsConnecting",
                &format!("Connecting (attempt {})...", attempt),
            );

            match connect_async(&url).await {
                Ok((mut ws_stream, _)) => {
                    attempt = 0;
                    call_kotlin(&vm, &ws_service_class, "onWsConnected", &url);

                    while let Some(msg) = ws_stream.next().await {
                        if STOP.load(Ordering::Relaxed) {
                            break;
                        }

                        match msg {
                            Ok(m) => {
                                let text = if m.is_text() {
                                    m.to_text().unwrap_or("").to_string()
                                } else if m.is_binary() {
                                    let data = m.into_data();
                                    let len = data.len().min(BUFFER_SIZE);
                                    buffer[..len].copy_from_slice(&data[..len]);
                                    std::str::from_utf8(&buffer[..len])
                                        .unwrap_or("")
                                        .to_string()
                                } else if m.is_close() {
                                    call_kotlin(
                                        &vm,
                                        &ws_service_class,
                                        "onWsDisconnected",
                                        "Server closed connection",
                                    );
                                    break;
                                } else {
                                    continue;
                                };

                                let truncated: String = text.chars().take(BUFFER_SIZE).collect();
                                call_kotlin(&vm, &ws_service_class, "onWsMessage", &truncated);
                            }
                            Err(e) => {
                                call_kotlin(
                                    &vm,
                                    &ws_service_class,
                                    "onWsError",
                                    &format!("Read error: {}", e),
                                );
                                break;
                            }
                        }
                    }

                    // Stream ended without an explicit close frame
                    if !STOP.load(Ordering::Relaxed) {
                        call_kotlin(&vm, &ws_service_class, "onWsDisconnected", "Connection lost");
                    }
                }
                Err(e) => {
                    call_kotlin(
                        &vm,
                        &ws_service_class,
                        "onWsError",
                        &format!("Connect failed: {}", e),
                    );
                    tokio::time::sleep(std::time::Duration::from_secs(3)).await;
                }
            }
        }

        call_kotlin(&vm, &ws_service_class, "onWsStopped", "Service stopped");
    });
}

#[no_mangle]
pub extern "system" fn Java_com_listener_WsService_stopWs(
    _env: JNIEnv,
    _class: JClass,
) {
    STOP.store(true, Ordering::Relaxed);
}
