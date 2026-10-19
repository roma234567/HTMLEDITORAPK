package com.htmleditor.app.py

// ------------------------------------------------------------------ узлы AST

sealed class Node {
    var line: Int = 1
}

sealed class Stmt : Node()

class ExprStmt(val e: Expr) : Stmt()
class AssignStmt(val targets: List<Expr>, val value: Expr) : Stmt()
class AugAssignStmt(val target: Expr, val op: String, val value: Expr) : Stmt()
class IfStmt(
    val cond: Expr,
    val thenBody: List<Stmt>,
    val elifs: List<Pair<Expr, List<Stmt>>>,
    val elseBody: List<Stmt>?
) : Stmt()

class WhileStmt(val cond: Expr, val body: List<Stmt>) : Stmt()
class ForStmt(val target: Expr, val iter: Expr, val body: List<Stmt>) : Stmt()
class FuncStmt(val name: String, val params: List<ParamNode>, val body: List<Stmt>) : Stmt()
class ReturnStmt(val value: Expr?) : Stmt()
class BreakStmt : Stmt()
class ContinueStmt : Stmt()
class PassStmt : Stmt()
class ImportStmt(val name: String, val alias: String?) : Stmt()
class FromImportStmt(
    val module: String,
    val names: List<Pair<String, String?>>,
    val star: Boolean
) : Stmt()

data class ParamNode(val name: String, val default: Expr?)

// -------------------------------------------------------------- выражения

sealed class Expr : Node()

class LitExpr(val v: PyVal) : Expr()
class NameExpr(val name: String) : Expr()
class TupleExpr(val items: List<Expr>) : Expr()
class ListExpr(val items: List<Expr>) : Expr()
class DictExpr(val keys: List<Expr>, val values: List<Expr>) : Expr()
class ListCompExpr(val result: Expr, val target: String, val iter: Expr, val cond: Expr?) : Expr()
class BinOpExpr(val op: String, val left: Expr, val right: Expr) : Expr()
class UnaryExpr(val op: String, val operand: Expr) : Expr()
class CompareExpr(val left: Expr, val ops: List<String>, val rights: List<Expr>) : Expr()
class BoolExpr(val op: String, val left: Expr, val right: Expr) : Expr()
class CallExpr(
    val fn: Expr,
    val args: List<Expr>,
    val kwargs: List<Pair<String, Expr>>
) : Expr()

class IndexExpr(val obj: Expr, val index: Expr) : Expr()
class SliceExpr(val lower: Expr?, val upper: Expr?, val step: Expr?) : Expr()
class AttrExpr(val obj: Expr, val name: String) : Expr()
data class FPart(val text: String, val expr: Expr?, val spec: String?)
class FStringExpr(val parts: List<FPart>) : Expr()
