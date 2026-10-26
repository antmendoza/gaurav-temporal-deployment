data "archive_file" "handler" {
  type        = "zip"
  source_dir  = "${path.module}/../../worker"
  output_path = "${path.module}/.build/handler.zip"
}

resource "aws_lambda_function" "worker" {
  function_name = local.name
  role          = aws_iam_role.lambda_exec.arn
  runtime       = "python3.12"
  handler       = "handler.handler"
  timeout       = 10

  filename         = data.archive_file.handler.output_path
  source_code_hash = data.archive_file.handler.output_base64sha256

  environment {
    variables = {
      TEMPORAL_ADDRESS = var.temporal_address
    }
  }
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/aws/lambda/${aws_lambda_function.worker.function_name}"
  retention_in_days = 14
}
