import { expect, type Locator, type Page } from '@playwright/test';

export type RentalForm = {
    reference: string;
    equipmentId?: number;
    quantity: number;
    days: number;
    premium: boolean;
};

export class RentalPage {
    readonly page: Page;
    readonly notice: Locator;
    readonly error: Locator;
    readonly rentalResult: Locator;
    readonly rentalId: Locator;
    readonly rentalStatus: Locator;
    readonly rentalTotal: Locator;

    constructor(page: Page) {
        this.page = page;
        this.notice = page.getByRole('status');
        this.error = page.getByRole('alert');
        this.rentalResult = page.getByTestId('rental-result');
        this.rentalId = page.getByTestId('rental-id');
        this.rentalStatus = page.getByTestId('rental-status');
        this.rentalTotal = page.getByTestId('rental-total');
    }

    async open() {
        await this.page.goto('/index.html');
    }

    async openRental(id: number) {
        await this.page.goto(`/index.html?rental=${id}`);
        await expect(this.notice).toHaveText('Оренду завантажено.');
    }

    async createEquipment(code: string, priceUah: number, stock: number) {
        await this.page.getByLabel('Код обладнання').fill(code);
        await this.page.getByLabel('Добова ціна, грн').fill(String(priceUah));
        await this.page.getByLabel('Залишок').fill(String(stock));
        await this.page.getByRole('button', { name: 'Створити обладнання' }).click();
        await expect(this.notice).toHaveText('Обладнання створено. Ідентифікатор підставлено у форму оренди.');
    }

    async createRental(form: RentalForm) {
        await this.page.getByLabel('Код оренди').fill(form.reference);
        if (form.equipmentId !== undefined) {
            await this.page.getByLabel('Ідентифікатор обладнання', { exact: true }).fill(String(form.equipmentId));
        }
        await this.page.getByLabel('Кількість', { exact: true }).fill(String(form.quantity));
        await this.page.getByLabel('Кількість днів').fill(String(form.days));
        await this.page.getByLabel('Преміальний клієнт').setChecked(form.premium);
        await this.page.getByRole('button', { name: 'Оформити оренду' }).click();
    }

    async returnEquipment() {
        await this.page.getByRole('button', { name: 'Повернути обладнання' }).click();
    }

    async expectRental(status: 'Active' | 'Returned', totalKopecks: number) {
        await expect(this.rentalResult).toBeVisible();
        await expect(this.rentalStatus).toHaveAttribute('data-status', status);
        await expect(this.rentalStatus).toHaveText(status === 'Active' ? 'Активна' : 'Повернено');
        await expect(this.rentalTotal).toHaveAttribute('data-kopecks', String(totalKopecks));
    }
}