output "web_server_public_ips" {
  value = aws_instance.web[*].public_ip
}

output "ansible_inventory" {
  value = abspath("${path.module}/../ansible/inventory.ini")
}
