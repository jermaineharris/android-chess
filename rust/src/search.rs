use shakmaty::{Chess, Color, Move, Piece, Position, Role, Square};

const PAWN: i32 = 100;
const KNIGHT: i32 = 320;
const BISHOP: i32 = 330;
const ROOK: i32 = 500;
const QUEEN: i32 = 900;
const MATE: i32 = 30_000;

const PAWN_PST: [i32; 64] = [
    0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 50, 50, 50, 50, 50, 50, 10, 10, 20, 30, 30, 20, 10, 10, 5, 5,
    10, 25, 25, 10, 5, 5, 0, 0, 0, 20, 20, 0, 0, 0, 5, -5, -10, 0, 0, -10, -5, 5, 5, 10, 10, -20,
    -20, 10, 10, 5, 0, 0, 0, 0, 0, 0, 0, 0,
];

const KNIGHT_PST: [i32; 64] = [
    -50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 0, 0, 0, -20, -40, -30, 0, 10, 15, 15, 10,
    0, -30, -30, 5, 15, 20, 20, 15, 5, -30, -30, 0, 15, 20, 20, 15, 0, -30, -30, 5, 10, 15, 15, 10,
    5, -30, -40, -20, 0, 5, 5, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50,
];

const BISHOP_PST: [i32; 64] = [
    -20, -10, -10, -10, -10, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 10, 10, 5, 0,
    -10, -10, 5, 5, 10, 10, 5, 5, -10, -10, 0, 10, 10, 10, 10, 0, -10, -10, 10, 10, 10, 10, 10, 10,
    -10, -10, 5, 0, 0, 0, 0, 5, -10, -20, -10, -10, -10, -10, -10, -10, -20,
];

const KING_MID: [i32; 64] = [
    -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40,
    -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -20, -30, -30, -40, -40, -30,
    -30, -20, -10, -20, -20, -20, -20, -20, -20, -10, 20, 20, 0, 0, 0, 0, 20, 20, 20, 30, 10, 0, 0,
    10, 30, 20,
];

fn role_value(role: Role) -> i32 {
    match role {
        Role::Pawn => PAWN,
        Role::Knight => KNIGHT,
        Role::Bishop => BISHOP,
        Role::Rook => ROOK,
        Role::Queen => QUEEN,
        Role::King => 0,
    }
}

fn pst_index(color: Color, sq: Square) -> usize {
    let i = usize::from(sq);
    if color == Color::White {
        i
    } else {
        i ^ 56
    }
}

fn piece_score(piece: Piece, sq: Square) -> i32 {
    let mut v = role_value(piece.role);
    v += match piece.role {
        Role::Pawn => PAWN_PST[pst_index(piece.color, sq)],
        Role::Knight => KNIGHT_PST[pst_index(piece.color, sq)],
        Role::Bishop => BISHOP_PST[pst_index(piece.color, sq)],
        Role::King => KING_MID[pst_index(piece.color, sq)],
        _ => 0,
    };
    v
}

pub fn evaluate(pos: &Chess) -> i32 {
    let mut score = 0;
    for sq in pos.board().occupied() {
        let piece = pos.board().piece_at(sq).unwrap();
        let s = piece_score(piece, sq);
        score += if piece.color == Color::White { s } else { -s };
    }
    if pos.turn() == Color::White {
        score
    } else {
        -score
    }
}

fn order_moves(moves: &mut [Move]) {
    moves.sort_by_key(|m| {
        let cap = m.capture().map(role_value).unwrap_or(0);
        let promo = m.promotion().map(role_value).unwrap_or(0);
        -(cap * 10 + promo)
    });
}

fn negamax(pos: &Chess, depth: i32, mut alpha: i32, beta: i32) -> i32 {
    let mut moves = pos.legal_moves();
    if moves.is_empty() {
        return if pos.is_check() { -MATE } else { 0 };
    }
    if depth <= 0 {
        return evaluate(pos);
    }

    order_moves(&mut moves);
    let mut best = i32::MIN / 2;
    for m in moves {
        let mut child = pos.clone();
        child.play_unchecked(&m);
        let score = -negamax(&child, depth - 1, -beta, -alpha);
        if score > best {
            best = score;
        }
        if best > alpha {
            alpha = best;
        }
        if alpha >= beta {
            break;
        }
    }
    best
}

pub fn best_move(pos: &Chess, depth: u8) -> Option<Move> {
    let depth = depth.max(1) as i32;
    let mut moves = pos.legal_moves();
    if moves.is_empty() {
        return None;
    }
    order_moves(&mut moves);
    let mut best_move = moves[0].clone();
    let mut best_score = i32::MIN / 2;
    let mut alpha = i32::MIN / 2;
    let beta = i32::MAX / 2;
    for m in moves {
        let mut child = pos.clone();
        child.play_unchecked(&m);
        let score = -negamax(&child, depth - 1, -beta, -alpha);
        if score > best_score {
            best_score = score;
            best_move = m.clone();
        }
        if score > alpha {
            alpha = score;
        }
    }
    Some(best_move)
}

pub fn depth_for_difficulty(difficulty: u8) -> u8 {
    match difficulty {
        0 => 1,
        1 => 2,
        2 => 3,
        _ => 4,
    }
}
