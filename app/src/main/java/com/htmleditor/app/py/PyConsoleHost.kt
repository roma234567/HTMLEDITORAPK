package com.htmleditor.app.py

import java.util.concurrent.LinkedBlockingQueue

/**
 * Хост исполнения Python: консоль + блокирующий ввод + остановка + turtle.
 * Интерпретатор работает в фоновом потоке; колбэки выполняются в этом потоке.
 */
class PyConsoleHost(
    private val emit: (HostEvent) -> Unit,
    private val bridge: TurtleBridge
) : ExecHost {

    sealed class HostEvent {
        data class Output(val text: String) : HostEvent()
        data class Prompt(val text: String) : HostEvent()
        object Finished : HostEvent()
        object Stopped : HostEvent()
        data class Error(val line: Int, val message: String) : HostEvent()
        object Done : HostEvent()
    }

    private val inputQueue = LinkedBlockingQueue<String>()
    private val sentinel = "__HTMLEDITOR_STOP_REQUESTED__"

    @Volatile
    var stopRequested = false
        private set

    @Volatile
    var running = false
        private set

    fun resetForNewRun() {
        stopRequested = false
        running = true
        inputQueue.clear()
    }

    fun requestStop() {
        stopRequested = true
        inputQueue.offer(sentinel)
    }

    fun feedInput(line: String) {
        inputQueue.offer(line)
    }

    override fun out(text: String) {
        emit(HostEvent.Output(text))
    }

    override fun prompt(text: String) {
        emit(HostEvent.Prompt(text))
    }

    override fun readLine(): String {
        val v = try {
            inputQueue.take()
        } catch (e: InterruptedException) {
            throw PyStop()
        }
        if (v == sentinel) throw PyStop()
        return v
    }

    override fun tick() {
        if (stopRequested) throw PyStop()
    }

    override fun turtle(): TurtleBridge = bridge

    /** Точка входа фонового потока: запуск с доставкой события завершения. */
    fun execute(code: String) {
        try {
            PyInterpreter(this).run(code)
            emit(HostEvent.Done)
        } catch (e: PyStop) {
            emit(HostEvent.Stopped)
        } catch (e: PyDone) {
            emit(HostEvent.Done)
        } catch (e: PyError) {
            emit(HostEvent.Error(e.line, e.message ?: "ошибка"))
        } catch (e: StackOverflowError) {
            emit(HostEvent.Error(0, "слишком глубокая рекурсия"))
        } catch (e: Exception) {
            emit(HostEvent.Error(0, "внутренняя ошибка: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            running = false
            emit(HostEvent.Finished)
        }
    }
}
