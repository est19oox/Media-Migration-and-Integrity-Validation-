output "server_public_ip" {
  value = aws_instance.web.public_ip
}

output "server_public_dns" {
  value = aws_instance.web.public_dns
}