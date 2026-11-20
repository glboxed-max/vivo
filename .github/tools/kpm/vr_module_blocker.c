/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * PD2339 boot-time module filter.
 *
 * The vendor kernel loads vr.ko from the first-stage ramdisk. This KPM is
 * embedded into the patched kernel image and loaded at the pre-kernel-init
 * event, so the syscall hooks are installed before Android init starts.
 * Only modules whose .modinfo name is exactly "vr" are skipped.
 */

#include <compiler.h>
#include <kpmodule.h>
#include <ksyms.h>
#include <kputils.h>
#include <linux/elf.h>
#include <linux/err.h>
#include <linux/fs.h>
#include <linux/printk.h>
#include <linux/string.h>
#include <linux/vmalloc.h>
#include <syscall.h>
#include <uapi/asm-generic/unistd.h>

KPM_NAME("vr_module_blocker");
KPM_VERSION("1.0.0");
KPM_LICENSE("GPL v2");
KPM_AUTHOR("PD2339 adaptation");
KPM_DESCRIPTION("Skip init_module/finit_module for the vendor vr module at boot");

#define VR_MAX_SECTIONS 512
#define VR_MAX_SHSTRTAB (64 * 1024)
#define VR_MAX_MODINFO (16 * 1024)

#ifndef SEEK_SET
#define SEEK_SET 0
#endif

#ifndef SEEK_END
#define SEEK_END 2
#endif

static void *(*vr_vmalloc)(unsigned long size);
static void (*vr_vfree)(const void *addr);
static unsigned long (*vr_copy_from_user)(void *to, const void __user *from, unsigned long n);
static struct file *(*vr_fget)(int fd);
static void (*vr_fput)(struct file *file);
static loff_t (*vr_vfs_llseek)(struct file *file, loff_t offset, int whence);
static ssize_t (*vr_kernel_read)(struct file *file, void *buf, size_t count, loff_t *pos);

struct vr_module_reader {
    const char __user *umod;
    unsigned long umod_len;
    struct file *file;
    loff_t file_size;
};

static void vr_resolve_symbols(void)
{
    vr_vmalloc = (typeof(vr_vmalloc))kallsyms_lookup_name("vmalloc");
    vr_vfree = (typeof(vr_vfree))kallsyms_lookup_name("vfree");

    const char *copy_names[] = {"copy_from_user", "_copy_from_user", "__copy_from_user", "raw_copy_from_user"};
    int i;
    for (i = 0; i < (int)(sizeof(copy_names) / sizeof(copy_names[0])); i++) {
        vr_copy_from_user = (typeof(vr_copy_from_user))kallsyms_lookup_name(copy_names[i]);
        if (vr_copy_from_user) break;
    }

    vr_fget = (typeof(vr_fget))kallsyms_lookup_name("fget");
    vr_fput = (typeof(vr_fput))kallsyms_lookup_name("fput");
    vr_vfs_llseek = (typeof(vr_vfs_llseek))kallsyms_lookup_name("vfs_llseek");
    vr_kernel_read = (typeof(vr_kernel_read))kallsyms_lookup_name("kernel_read");
}

static int vr_reader_read(struct vr_module_reader *reader, loff_t offset, void *buf, size_t len)
{
    loff_t total = reader->umod ? (loff_t)reader->umod_len : reader->file_size;

    if (len == 0) return -1;
    if (offset < 0 || (loff_t)len > total || offset > total - (loff_t)len) return -1;

    if (reader->umod) {
        if (!vr_copy_from_user) return -1;
        if (vr_copy_from_user(buf, reader->umod + offset, len) != 0) return -1;
        return 0;
    }

    {
        loff_t pos = offset;
        ssize_t n = vr_kernel_read(reader->file, buf, len, &pos);
        if (n < 0 || (size_t)n != len) return -1;
    }
    return 0;
}

static bool vr_module_is_target(struct vr_module_reader *reader)
{
    Elf64_Ehdr ehdr;
    Elf64_Shdr *shdrs = NULL;
    char *shstrtab = NULL;
    char *modinfo = NULL;
    Elf64_Shdr *shstr_sh;
    Elf64_Shdr *modinfo_sh = NULL;
    unsigned int shnum;
    unsigned int i;
    unsigned long shtab_bytes;
    bool is_vr = false;

    if (vr_reader_read(reader, 0, &ehdr, sizeof(ehdr))) return false;
    if (memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0) return false;
    if (ehdr.e_ident[EI_CLASS] != ELFCLASS64) return false;
    if (ehdr.e_type != ET_REL) return false;
    if (ehdr.e_shentsize != sizeof(Elf64_Shdr)) return false;

    shnum = ehdr.e_shnum;
    if (shnum == 0 || shnum > VR_MAX_SECTIONS) return false;
    if (ehdr.e_shstrndx >= shnum) return false;

    shtab_bytes = (unsigned long)shnum * sizeof(Elf64_Shdr);
    shdrs = vr_vmalloc(shtab_bytes);
    if (!shdrs) return false;
    if (vr_reader_read(reader, ehdr.e_shoff, shdrs, shtab_bytes)) goto out;

    shstr_sh = &shdrs[ehdr.e_shstrndx];
    if (shstr_sh->sh_size == 0 || shstr_sh->sh_size > VR_MAX_SHSTRTAB) goto out;

    shstrtab = vr_vmalloc(shstr_sh->sh_size);
    if (!shstrtab) goto out;
    if (vr_reader_read(reader, shstr_sh->sh_offset, shstrtab, shstr_sh->sh_size)) goto out;

    for (i = 0; i < shnum; i++) {
        Elf64_Shdr *sh = &shdrs[i];
        const char *name;
        unsigned long remaining;

        if (sh->sh_name >= shstr_sh->sh_size) continue;
        name = shstrtab + sh->sh_name;
        remaining = shstr_sh->sh_size - sh->sh_name;
        if (strnlen(name, remaining) >= remaining) continue;
        if (strcmp(name, ".modinfo") == 0) {
            modinfo_sh = sh;
            break;
        }
    }

    if (!modinfo_sh) goto out;
    if (modinfo_sh->sh_size == 0 || modinfo_sh->sh_size > VR_MAX_MODINFO) goto out;

    modinfo = vr_vmalloc(modinfo_sh->sh_size);
    if (!modinfo) goto out;
    if (vr_reader_read(reader, modinfo_sh->sh_offset, modinfo, modinfo_sh->sh_size)) goto out;

    {
        const char *p = modinfo;
        const char *end = modinfo + modinfo_sh->sh_size;

        while (p < end) {
            const char *nul = memchr(p, '\0', end - p);
            size_t entlen;

            if (!nul) break;
            entlen = nul - p;
            if (entlen > 5 && memcmp(p, "name=", 5) == 0) {
                const char *value = p + 5;
                size_t vlen = entlen - 5;
                if (vlen == 2 && value[0] == 'v' && value[1] == 'r') is_vr = true;
                break;
            }
            p = nul + 1;
        }
    }

out:
    if (modinfo) vr_vfree(modinfo);
    if (shstrtab) vr_vfree(shstrtab);
    if (shdrs) vr_vfree(shdrs);
    return is_vr;
}

static void before_init_module(hook_fargs3_t *args, void *udata)
{
    struct vr_module_reader reader = {
        .umod = (const char __user *)syscall_argn(args, 0),
        .umod_len = (unsigned long)syscall_argn(args, 1),
        .file = NULL,
        .file_size = 0,
    };

    if (!reader.umod || reader.umod_len < sizeof(Elf64_Ehdr)) return;
    if (!vr_copy_from_user || !vr_vmalloc || !vr_vfree) return;
    if (!vr_module_is_target(&reader)) return;

    args->skip_origin = 1;
    args->ret = 0;
    pr_info("pd2339 vr blocker: blocked vr (init_module)\n");
}

static void before_finit_module(hook_fargs3_t *args, void *udata)
{
    int fd = (int)syscall_argn(args, 0);
    struct file *file;
    struct vr_module_reader reader;
    loff_t file_size;

    if (!vr_fget || !vr_fput || !vr_vfs_llseek || !vr_kernel_read || !vr_vmalloc || !vr_vfree) return;

    file = vr_fget(fd);
    if (!file) return;

    file_size = vr_vfs_llseek(file, 0, SEEK_END);
    vr_vfs_llseek(file, 0, SEEK_SET);
    if (file_size < (loff_t)sizeof(Elf64_Ehdr)) {
        vr_fput(file);
        return;
    }

    reader.umod = NULL;
    reader.umod_len = 0;
    reader.file = file;
    reader.file_size = file_size;

    if (vr_module_is_target(&reader)) {
        args->skip_origin = 1;
        args->ret = 0;
        pr_info("pd2339 vr blocker: blocked vr (finit_module)\n");
    }
    vr_fput(file);
}

static long vr_module_blocker_init(const char *args, const char *event, void *reserved)
{
    hook_err_t err;

    vr_resolve_symbols();
    if (!vr_vmalloc || !vr_vfree) {
        pr_err("pd2339 vr blocker: allocator symbols unavailable\n");
        return 0;
    }

    if (vr_copy_from_user) {
        err = hook_syscalln(__NR_init_module, 3, before_init_module, NULL, NULL);
        if (err) pr_err("pd2339 vr blocker: init_module hook failed: %d\n", err);
    }

    if (vr_fget && vr_fput && vr_vfs_llseek && vr_kernel_read) {
        err = hook_syscalln(__NR_finit_module, 3, before_finit_module, NULL, NULL);
        if (err) pr_err("pd2339 vr blocker: finit_module hook failed: %d\n", err);
    }

    pr_info("pd2339 vr blocker: installed, event=%s\n", event ? event : "unknown");
    return 0;
}

static long vr_module_blocker_exit(void *reserved)
{
    if (vr_copy_from_user) unhook_syscalln(__NR_init_module, before_init_module, NULL);
    unhook_syscalln(__NR_finit_module, before_finit_module, NULL);
    pr_info("pd2339 vr blocker: removed\n");
    return 0;
}

KPM_INIT(vr_module_blocker_init);
KPM_EXIT(vr_module_blocker_exit);
