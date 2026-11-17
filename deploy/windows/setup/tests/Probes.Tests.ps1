BeforeAll {
    Import-Module (Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1') -Force -DisableNameChecking
}

Describe 'Disk probes use the actual path' {
    It 'reports the drive that contains the given path' {
        $path = Join-Path $TestDrive 'install\app'
        New-Item -ItemType Directory -Force -Path $path | Out-Null
        $disk = Get-OmDiskInfo -Path $path
        $expectedRoot = [IO.Path]::GetPathRoot([IO.Path]::GetFullPath($path))
        $disk.drive | Should -Be $expectedRoot
        $disk.path | Should -Be ([IO.Path]::GetFullPath($path))
        $disk.freeGB | Should -Not -BeNullOrEmpty
        $disk.freeGB | Should -BeGreaterOrEqual 0
    }

    It 'parses WSL BasePath drive letters' {
        ConvertFrom-OmWslBasePath -BasePath '\\?\E:\DockerDesktopWSL\main' | Should -Be 'E:\'
        ConvertFrom-OmWslBasePath -BasePath 'D:\DockerDesktopWSL\main' | Should -Be 'D:\'
        ConvertFrom-OmWslBasePath -BasePath '\\?\Volume{01234567-89ab-cdef-0123-456789abcdef}\Docker' | Should -BeNullOrEmpty
        ConvertFrom-OmWslBasePath -BasePath '' | Should -BeNullOrEmpty
    }

    It 'reads docker-desktop BasePath from the Lxss registry' {
        $root = [IO.Path]::GetPathRoot([IO.Path]::GetFullPath($TestDrive))
        $regRoot = 'HKCU:\Software\OpenMarginTests\' + [guid]::NewGuid().ToString('N')
        $distroKey = Join-Path $regRoot 'docker-desktop'
        try {
            New-Item -Path $distroKey -Force | Out-Null
            New-ItemProperty -Path $distroKey -Name DistributionName -Value 'docker-desktop' -PropertyType String -Force | Out-Null
            New-ItemProperty -Path $distroKey -Name BasePath -Value ('\\?\' + $root.TrimEnd('\') + '\DockerDesktopWSL\main') -PropertyType String -Force | Out-Null
            $docker = Get-OmDockerDataDrive -RegistryRoot $regRoot -FallbackPath $TestDrive
            $docker.drive | Should -Be $root
            $docker.source | Should -Be 'docker-desktop'
            $docker.basePath | Should -Match 'DockerDesktopWSL'
        } finally {
            Remove-Item -Path $regRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    It 'falls back to the install drive when Docker data is not configured' {
        $docker = Get-OmDockerDataDrive -RegistryRoot ('HKCU:\Software\OpenMarginTests\' + [guid]::NewGuid().ToString('N')) -FallbackPath $TestDrive
        $docker.source | Should -Be 'fallback'
        $docker.drive | Should -Be (Get-OmDiskInfo -Path $TestDrive).drive
    }
}

Describe 'Environment disk checks' {
    It 'passes D: install and warns on E: Docker data' {
        Mock -ModuleName OpenMargin.Setup Get-OmDiskInfo {
            return [pscustomobject]@{ drive = 'D:\'; path = 'D:\OpenMargin\app'; freeGB = 53.4; ok = $true }
        }
        Mock -ModuleName OpenMargin.Setup Get-OmDockerDataDrive {
            return [pscustomobject]@{ drive = 'E:\'; freeGB = 4.2; basePath = '\\?\E:\DockerDesktopWSL\main'; source = 'docker-desktop' }
        }
        $checks = @(Get-OmEnvironmentChecks -SkipNetwork -SkipPorts -RepoRoot 'D:\OpenMargin\app')
        $disk = $checks | Where-Object { $_.id -eq 'disk' }
        $docker = $checks | Where-Object { $_.id -eq 'docker-disk' }
        $disk.status | Should -Be 'ok'
        $disk.detail | Should -Match 'D:'
        $docker.status | Should -Be 'warn'
        $docker.code | Should -Be 'DOCKER_DISK_TIGHT'
        $docker.detail | Should -Match 'E:'
        $docker.detail | Should -Match '4.2GB'
    }

    It 'fails below 2GB install and 3GB Docker data' {
        Mock -ModuleName OpenMargin.Setup Get-OmDiskInfo {
            return [pscustomobject]@{ drive = 'D:\'; path = 'D:\OpenMargin\app'; freeGB = 1.5; ok = $false }
        }
        Mock -ModuleName OpenMargin.Setup Get-OmDockerDataDrive {
            return [pscustomobject]@{ drive = 'E:\'; freeGB = 2.5; basePath = '\\?\E:\DockerDesktopWSL\main'; source = 'docker-desktop' }
        }
        $checks = @(Get-OmEnvironmentChecks -SkipNetwork -SkipPorts -RepoRoot 'D:\OpenMargin\app')
        ($checks | Where-Object { $_.id -eq 'disk' }).code | Should -Be 'DISK_LOW'
        ($checks | Where-Object { $_.id -eq 'docker-disk' }).code | Should -Be 'DOCKER_DISK_LOW'
    }

    It 'warns from 2GB to 5GB on both drives' {
        Mock -ModuleName OpenMargin.Setup Get-OmDiskInfo {
            return [pscustomobject]@{ drive = 'D:\'; path = 'D:\OpenMargin\app'; freeGB = 4.9; ok = $true }
        }
        Mock -ModuleName OpenMargin.Setup Get-OmDockerDataDrive {
            return [pscustomobject]@{ drive = 'E:\'; freeGB = 4.9; basePath = '\\?\E:\DockerDesktopWSL\main'; source = 'docker-desktop' }
        }
        $checks = @(Get-OmEnvironmentChecks -SkipNetwork -SkipPorts -RepoRoot 'D:\OpenMargin\app')
        ($checks | Where-Object { $_.id -eq 'disk' }).code | Should -Be 'DISK_TIGHT'
        ($checks | Where-Object { $_.id -eq 'docker-disk' }).code | Should -Be 'DOCKER_DISK_TIGHT'
    }
}
