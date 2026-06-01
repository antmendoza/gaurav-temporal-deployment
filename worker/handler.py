def handler(event, context):
    return {
        "message": "hello from temporal-worker lambda",
        "event": event,
    }
