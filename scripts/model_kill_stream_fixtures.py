"""Local scripted streams for the process-kill runner; based on repository decoder fixtures."""
import json


def event(kind, **fields):
    return f"event: {kind}\ndata: " + json.dumps(dict(type=kind, **fields)) + "\n\n"


def responses_stream(tools=False, held=False):
    stream = event("response.created", sequence_number=0)
    stream += event("response.in_progress", sequence_number=1)
    text = "partial" if held else "ok"
    if tools:
        args = '{"text":"probe"}'
        item = dict(id="fc_1", type="function_call", status="in_progress", call_id="call_1", name="echo", arguments="")
        stream += event("response.output_item.added", output_index=0, item=item)
        stream += event("response.function_call_arguments.delta", item_id="fc_1", output_index=0, delta=args)
        stream += event("response.function_call_arguments.done", item_id="fc_1", output_index=0, arguments=args)
        item.update(status="completed", arguments=args)
    else:
        item = dict(id="msg_1", type="message", status="in_progress", role="assistant", content=[])
        stream += event("response.output_item.added", output_index=0, item=item)
        stream += event("response.content_part.added", item_id="msg_1", output_index=0, content_index=0)
        stream += event("response.output_text.delta", item_id="msg_1", output_index=0, content_index=0, delta=text)
        if held:
            return stream
        stream += event("response.output_text.done", item_id="msg_1", output_index=0, content_index=0, text=text)
        item.update(status="completed", content=[dict(type="output_text", text=text)])
    stream += event("response.output_item.done", output_index=0, item=item)
    stream += event("response.completed", response=dict(id="resp_1", object="response", status="completed",
        usage=dict(input_tokens=10, output_tokens=2, total_tokens=12)))
    return stream


def anthropic_stream(tools=False, held=False):
    stream = event("message_start", message=dict(id="msg_1", type="message", role="assistant",
        model="fixture-model-a", content=[], usage=dict(input_tokens=10, output_tokens=1)))
    if tools:
        stream += event("content_block_start", index=0, content_block=dict(type="tool_use", id="call_1", name="echo", input={}))
        stream += event("content_block_delta", index=0, delta=dict(type="input_json_delta", partial_json='{"text":"probe"}'))
    else:
        stream += event("content_block_start", index=0, content_block=dict(type="text", text=""))
        stream += event("content_block_delta", index=0, delta=dict(type="text_delta", text="partial" if held else "ok"))
        if held:
            return stream
    stream += event("content_block_stop", index=0)
    stream += event("message_delta", delta=dict(stop_reason="tool_use" if tools else "end_turn", stop_sequence=None),
        usage=dict(output_tokens=2))
    return stream + event("message_stop")
