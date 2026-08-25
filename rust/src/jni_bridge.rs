use jni::objects::{JClass, JString};
use jni::sys::jboolean;
use jni::JNIEnv;
use std::sync::Mutex;

use crate::game::Game;

static GAME: Mutex<Option<Game>> = Mutex::new(None);

fn with_game<F: FnOnce(&mut Game)>(f: F) -> String {
    let mut guard = GAME.lock().expect("game mutex");
    match guard.as_mut() {
        Some(game) => {
            f(game);
            game.to_json()
        }
        None => serde_json::json!({ "error": "no game" }).to_string(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_huttsmedia_chess_ChessNative_newGame<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    vs_ai: jboolean,
    play_as_white: jboolean,
    difficulty: i32,
) -> jni::sys::jstring {
    let game = Game::new(vs_ai != 0, play_as_white != 0, difficulty.clamp(0, 3) as u8);
    let json = game.to_json();
    *GAME.lock().expect("game mutex") = Some(game);
    _env.new_string(json)
        .expect("string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_huttsmedia_chess_ChessNative_onSquareClick<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    row: i32,
    col: i32,
) -> jni::sys::jstring {
    let json = with_game(|g| g.click(row as u8, col as u8));
    env.new_string(json)
        .expect("string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_huttsmedia_chess_ChessNative_promote<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    piece: JString<'local>,
) -> jni::sys::jstring {
    let name: String = env.get_string(&piece).expect("piece").into();
    let json = with_game(|g| g.promote(&name));
    env.new_string(json)
        .expect("string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_huttsmedia_chess_ChessNative_undo<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jni::sys::jstring {
    let json = with_game(|g| g.undo());
    env.new_string(json)
        .expect("string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_huttsmedia_chess_ChessNative_aiMove<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jni::sys::jstring {
    let json = with_game(|g| g.play_ai());
    env.new_string(json)
        .expect("string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_huttsmedia_chess_ChessNative_getState<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jni::sys::jstring {
    let json = with_game(|_| {});
    env.new_string(json)
        .expect("string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_huttsmedia_chess_ChessNative_deselect<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jni::sys::jstring {
    let json = with_game(|g| g.deselect());
    env.new_string(json)
        .expect("string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_huttsmedia_chess_ChessNative_toggleFlip<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jni::sys::jstring {
    let json = with_game(|g| g.toggle_flip());
    env.new_string(json)
        .expect("string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_huttsmedia_chess_ChessNative_resign<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jni::sys::jstring {
    let json = with_game(|g| g.resign());
    env.new_string(json)
        .expect("string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_huttsmedia_chess_ChessNative_exportSave<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jni::sys::jstring {
    let json = {
        let guard = GAME.lock().expect("game mutex");
        match guard.as_ref() {
            Some(game) => game.export_save(),
            None => serde_json::json!({ "error": "no game" }).to_string(),
        }
    };
    env.new_string(json)
        .expect("string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_huttsmedia_chess_ChessNative_importSave<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    save: JString<'local>,
) -> jni::sys::jstring {
    let raw: String = env.get_string(&save).expect("save").into();
    let json = match Game::import_save(&raw) {
        Ok(game) => {
            let out = game.to_json();
            *GAME.lock().expect("game mutex") = Some(game);
            out
        }
        Err(err) => serde_json::json!({ "error": err }).to_string(),
    };
    env.new_string(json)
        .expect("string")
        .into_raw()
}
